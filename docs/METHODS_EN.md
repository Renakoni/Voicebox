<p align="center">
  <a href="METHODS.md">中文</a> · <a href="METHODS_JA.md">日本語</a> · <a href="METHODS_EN.md">English</a>
</p>

# Architecture and Optimizations

Voice cloning begins with two text inputs and a reference audio clip: the transcript of the reference recording and the target text to synthesize. The pipeline first extracts speaker acoustic representations from the reference audio and its transcript, predicts an autoregressive semantic token sequence conditioned on the target text, and then feeds these tokens into the acoustic model and vocoder to synthesize the final playable time-domain waveform.

Voicebox builds upon the GPT-SoVITS architecture and Genie-TTS's Android engineering foundation. On compute- and memory-constrained mobile devices, minimizing tensor memory I/O and heterogeneous inter-processor data transfers (CPU, GPU, NPU)—in addition to raw arithmetic FLOPs—is essential for achieving real-time performance.

## From Reference Audio to Semantic Tokens

When synthesizing multiple consecutive utterances with the same voice, the reference audio, its transcript, and the extracted speaker embeddings remain constant. The system caches these precomputed representations in memory for immediate reuse across subsequent sentences, invalidating and recomputing them only when the reference prompt or audio changes.

Autoregressive generation in the semantic model requires attending over the entire history at each decoding step. Because past keys and values remain unchanged in future steps, the system caches them in a KV Cache. To prevent frequent heap reallocations and memory copies on mobile devices, the decoder pre-allocates contiguous memory buffers and writes new states in place, triggering incremental expansions only when initial capacity is exceeded.

Generating one token per step makes autoregressive decoding strictly memory-bandwidth bound. We store model weights in FP16, halving the memory footprint and read bandwidth. During computation, weights are upcast to FP32 as needed for multiply-accumulate operations, alleviating memory bus pressure with zero impact on numerical precision.

For token sampling, the pipeline adopts GPT-SoVITS's exponential sampling strategy: drawing independent exponential random variates and dividing them by predicted probabilities, transforming sampling into a minimum-waiting-time problem. This method mathematically matches the categorical distribution without requiring explicit probability normalization or sorting, allowing one-pass online sampling directly into the autoregressive history.

## V2 Pro Plus

### Acoustic Feature Expansion and Heterogeneous Partitioning

Once the semantic sequence is generated, the acoustic model combines it with target text representations and speaker conditioning to form time-aligned acoustic features. The vocoder then performs hierarchical upsampling to produce 32 kHz waveforms at 640 audio samples per feature frame.

Pre-vocoder logic involves variable-length sequences and masking operations with numerous control branches, which are orchestrated on the CPU in FP32. While the vocoder itself is compute-intensive, feature tensors expand rapidly in the time domain. Transferring partially upsampled tensors between processors would incur heavy memory copy overhead. Therefore, the heterogeneous handoff boundary is placed strictly at the vocoder entrance: the CPU constructs the compact acoustic features and transfers them to the GPU in a single call, allowing the GPU to run the entire sequence of multi-stage transposed convolutions without intermediate host-device synchronization.

### Polyphase Decomposition for Transposed Convolution

Vocoders rely heavily on transposed convolutions for temporal upsampling. Naive implementations involve explicit zero-insertion, wasting compute on zero-multiplications. Using polyphase decomposition, we partition the convolution kernels into independent sub-kernel banks according to output phase: the GPU computes parallel sub-convolutions directly on the original dense input and interleaves the results. This eliminates zero-padding overhead and significantly accelerates upsampling while maintaining exact mathematical equivalence.

### Windowed Synthesis and NPU Acceleration for Long Audio

Convolutional vocoders exhibit strictly local temporal receptive fields, meaning an output waveform sample depends only on a bounded context of neighboring feature frames. Consequently, long passages can be partitioned into equal-length windows and executed on an NPU compiled for fixed-length shapes.

To ensure waveform continuity across window seams, the pipeline adopts a 64-frame baseline window with an 8-frame context halo on each side. During overlap-add stitching, each window only retains waveform segments where the receptive field is complete, while edge regions are filled by adjacent windows, eliminating phase discontinuities and audible artifacts.

In terms of execution, the CPU first integrates global speaker conditions into the full feature tensor in FP32; the heavy intermediate convolution layers are windowed and processed on the NPU in FP16; and the final waveform projection is calculated on the CPU in FP32 to avoid precision truncation and noise. For short utterances where fixed-window overhead and inter-processor scheduling dominate, the system automatically falls back to full-GPU execution, engaging the NPU windowed pipeline only when sequence length amortizes scheduling latency.

## Architectural Divergence Between Models

V2 Pro Plus's vocoder has purely local temporal dependencies, allowing straightforward sliding-window partitioning based on convolutional receptive fields. Conversely, V5 Turbo generates intermediate mel-spectrograms via a diffusion model where self-attention captures global temporal context across the chunk. Slicing this stage into smaller windows would compromise attention visibility. V5 Turbo therefore preserves chunk-level granularity for spectrogram generation and accelerates inference through staged pipelining and heterogeneous scheduling.

## V5 Turbo

### Spectrogram Chunking and Sequential Conditioning

V5 Turbo predicts time-aligned mel-spectrograms with a Diffusion Transformer (DiT), which the vocoder then synthesizes into 48 kHz audio. Because self-attention complexity scales quadratically with sequence length, long passages are divided into temporal chunks solved sequentially.

The first chunk conditions on the original reference audio. For subsequent chunks, the pipeline extracts the tail of the mel-spectrogram generated by the preceding chunk and splices it into the reference prompt. This preserves the speaker's global timbre while providing seamless acoustic and prosodic continuity. Conditioning embeddings and positional encodings are computed once per chunk.

Mobile NPUs typically require static tensor dimensions. Compiling separate engines for every dynamic length incurs prohibitive compilation latency, while padding to a global maximum wastes computation on short segments. The pipeline uses a bucketed compilation strategy: fixed discrete length buckets are precompiled and cached. At runtime, the input is padded to the smallest viable bucket, and excess tokens are masked out in attention. Compilation sessions release initial weight handles immediately to avoid holding redundant model copies in memory.

### Four-Step Diffusion Inference with Hybrid Precision

Each mel-spectrogram chunk starts from Gaussian noise and is refined through four DiT solver steps. Semantic conditions and frame positional encodings remain static within a chunk; timestep embeddings also have only four discrete states and are precomputed once per chunk.

Because attention and feed-forward networks within each DiT block can be executed in parallel across the chunk, we employ a hybrid-precision strategy:
- Steps 1–3: The heaviest matrix multiplications are offloaded to the NPU in FP16, keeping intermediate activations entirely on-device.
- GELU Fast Approximation: DiT layers frequently call GELU activations. The standard implementation uses hyperbolic tangent (Tanh), which is expensive on mobile NPUs. We replace this with a hardware-efficient Sigmoid approximation:
  $$\text{GELU}(x) \approx x \cdot \sigma(1.702x)$$
  This leverages native vectorized Sigmoid instructions, delivering measurable speedups with zero perceptual degradation.
- Step 4: The final refinement directly determines the dynamic range and fidelity of the resulting spectrogram. This step is executed on the GPU in FP32 to prevent quantization noise and artifacts before feeding into the vocoder.

### Dual-Engine Pipelining and Seamless Stitching

Chunked generation allows seamless asynchronous pipelining across heterogeneous processors: while the NPU runs Steps 1–3 of chunk $k+1$, the GPU concurrently executes Step 4 and vocoder synthesis for chunk $k$, maximizing mobile chip utilization.

Vocoder convolutions also require surrounding spectrogram context at chunk boundaries. When synthesizing a chunk, the system extracts the center waveform with complete receptive coverage and defers boundary segments until the next chunk's head spectrogram is available. Concatenating these segments along the time axis yields a smooth, continuous waveform with zero phase clicks.
