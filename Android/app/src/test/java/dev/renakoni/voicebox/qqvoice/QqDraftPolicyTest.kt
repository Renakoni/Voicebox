package dev.renakoni.voicebox.qqvoice

import org.junit.Assert.*
import org.junit.Test

class QqDraftPolicyTest {
    private val path = "${QqDraftPolicy.ROOT}/100001/ptt/202611/13/stream_2026111316485608.slk"
    private val draft = QqDraftFile(path, 14000, 101)
    @Test fun rejectsPathsOutsideOutgoingDraftLayout() {
        assertTrue(QqDraftPolicy.validPath(path))
        listOf(path.replace("/ptt/", "/ptt/../"), path + "'", path.replace("stream_", ""),
            path.replace("com.tencent.mobileqq", "other.app"), path.replace("100001/", "name/"))
            .forEach { assertFalse(it, QqDraftPolicy.validPath(it)) }
    }
    @Test fun selectsOnlyOneNewFreshDraft() {
        assertEquals(draft, QqDraftPolicy.select(emptyList(), listOf(draft), 100, 110))
        assertThrows(IllegalStateException::class.java) { QqDraftPolicy.select(listOf(draft), listOf(draft.copy(size = 20000)), 100, 110) }
        assertThrows(IllegalStateException::class.java) { QqDraftPolicy.select(emptyList(), listOf(draft, draft.copy(path = path.replace("5608", "5609"))), 100, 110) }
        assertThrows(IllegalStateException::class.java) { QqDraftPolicy.select(emptyList(), listOf(draft.copy(modified = 99)), 100, 110) }
    }
    @Test fun expiredOrClockReversedSessionCannotReplace() {
        for (now in listOf(99L, 281L)) assertThrows(IllegalStateException::class.java) {
            QqDraftPolicy.select(emptyList(), listOf(draft), 100, now)
        }
    }
}
