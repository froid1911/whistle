package dev.whistle.app.ui.changes

import dev.whistle.app.protocol.BridgeError
import dev.whistle.app.ui.chat.DiffKind
import dev.whistle.app.ui.chat.DiffLine
import dev.whistle.app.ui.chat.DraftStore
import dev.whistle.app.ui.chat.parseUnifiedDiff
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend : ChangesBackend {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var commitError: BridgeError? = null
    var worktrees: String = """{"worktrees":[{"path":"/wt/feature","branch":"f"},{"path":"/repo"}]}"""
    override suspend fun cmd(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return when (method) {
            "git.status" -> Json.parseToJsonElement("""{"branch":"main","files":[{"path":"a.kt","status":"M","staged":false}]}""")
            "git.diff" -> Json.parseToJsonElement("""{"diff":"x","truncated":false}""")
            "git.commit" -> commitError?.let { throw it } ?: Json.parseToJsonElement("""{"commit":"abc1234"}""")
            "worktree.list" -> Json.parseToJsonElement(worktrees)
            else -> JsonPrimitive("ok")
        }
    }
}

private const val DIFF = """diff --git a/a.kt b/a.kt
index 1..2 100644
--- a/a.kt
+++ b/a.kt
@@ -10,4 +10,5 @@ fun x
 ctx
-old
+new1
+new2
 tail
@@ -50 +51,2 @@
 c2
+added"""

class ChangesViewModelTest {
    private fun vm(b: FakeBackend, d: DraftStore = DraftStore()) = ChangesViewModel("m1", b, d)

    @Test fun lineNumbersMapOldAndNewFiles() {
        val n = numberDiff(parseUnifiedDiff(DIFF)).filter { it.kind != DiffKind.Header && it.kind != DiffKind.Hunk }
        assertEquals(listOf("ctx", "old", "new1", "new2", "tail", "c2", "added"), n.map { it.text })
        // removed -> old-file line; added/context -> new-file line
        assertEquals(listOf(10, 11, 11, 12, 13, 51, 52), n.map { it.refNo })
        assertNull(n[1].newNo)
        assertNull(n[2].oldNo)
        assertNull(numberDiff(listOf(DiffLine(DiffKind.Hunk, "@@ -1 +1 @@")))[0].refNo)
    }

    @Test fun askAgentDraftQuotesLine() = runTest {
        val drafts = DraftStore()
        val vm = vm(FakeBackend(), drafts)
        val lines = numberDiff(parseUnifiedDiff(DIFF))
        val removed = lines.first { it.kind == DiffKind.Removed }
        drafts.set("m1", "w1:p1", "already typed")
        assertTrue(vm.askAgent("a.kt", removed, "w1:p1"))
        assertEquals("already typed\nIn a.kt:11:\n> old\n", drafts.get("m1", "w1:p1"))
        val added = lines.first { it.text == "new2" }
        vm.askAgent("a.kt", added, "w1:p2")
        assertEquals("In a.kt:12:\n> new2\n", drafts.get("m1", "w1:p2"))
        // headers / hunks are not askable and add nothing
        assertEquals(false, vm.askAgent("a.kt", lines.first { it.kind == DiffKind.Hunk }, "w1:p3"))
        assertEquals("", drafts.get("m1", "w1:p3"))
    }

    @Test fun commitCallsGitCommitAll() = runTest {
        val b = FakeBackend()
        val vm = vm(b)
        vm.selectPath("/repo")
        b.calls.clear()
        vm.commit("   ")
        assertTrue(b.calls.isEmpty()) // blank message never reaches the bridge
        vm.commit("  fix it ")
        val commit = b.calls.first { it.first == "git.commit" }.second
        assertEquals("/repo", commit["path"]!!.jsonPrimitive.content)
        assertEquals("fix it", commit["message"]!!.jsonPrimitive.content)
        assertEquals("true", commit["all"]!!.jsonPrimitive.content)
        assertEquals(CommitState.Done("abc1234"), vm.state.value.commit)
        assertEquals("git.status", b.calls.last().first) // refreshed afterwards
    }

    @Test fun commitErrorShowsBridgeMessage() = runTest {
        val b = FakeBackend().apply { commitError = BridgeError("git_error", "nothing to commit") }
        val vm = vm(b)
        vm.selectPath("/repo")
        vm.commit("m")
        assertEquals(CommitState.Error("nothing to commit"), vm.state.value.commit)
    }

    @Test fun worktreePathsMergeIntoPicker() = runTest {
        val b = FakeBackend()
        val vm = vm(b)
        vm.setPanePaths(listOf("/repo", "/repo", "/other"), active = "/other")
        assertEquals("/other", vm.state.value.path)
        vm.loadWorktrees()
        assertEquals(listOf("/repo", "/other", "/wt/feature"), vm.state.value.paths)
    }

    @Test fun openFileLoadsNumberedDiff() = runTest {
        val b = FakeBackend()
        val vm = vm(b)
        vm.selectPath("/repo")
        vm.openFile("a.kt")
        val d = b.calls.first { it.first == "git.diff" }.second
        assertEquals("a.kt", d["file"]!!.jsonPrimitive.content)
        assertEquals("a.kt", vm.state.value.file)
    }

    @Test fun commitDialogExposesFilesThatWillBeCommitted() = runTest {
        val vm = vm(FakeBackend())
        vm.selectPath("/repo")
        assertEquals(listOf("a.kt"), vm.state.value.files.map { it.path })
        assertEquals("Commit 1 file", commitHeading(vm.state.value.files))
        assertEquals("Commit 3 files", commitHeading(List(3) { GitFile("f$it", "M", false) }))
    }

    @Test fun diffIsParsedOnceIntoLines() = runTest {
        val vm = vm(FakeBackend())
        vm.selectPath("/repo")
        vm.openFile("a.kt")
        assertEquals(vm.state.value.diff.map { it.text }, vm.state.value.diffLines.map { it.text })
    }
}
