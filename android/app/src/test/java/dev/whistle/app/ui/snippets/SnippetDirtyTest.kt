package dev.whistle.app.ui.snippets

import dev.whistle.app.data.SnippetEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetDirtyTest {
    private val s = SnippetEntity(5, "t", "body")

    @Test fun dirtyOnlyWhenEdited() {
        assertFalse(snippetDirty(s, "t", "body"))
        assertTrue(snippetDirty(s, "t", "body!"))
        assertTrue(snippetDirty(s, "x", "body"))
        // a new snippet with typed text is dirty (dismiss must ask)
        assertTrue(snippetDirty(SnippetEntity(title = "", text = ""), "", "typed"))
        assertFalse(snippetDirty(SnippetEntity(title = "", text = ""), "", ""))
    }
}
