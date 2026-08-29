package com.alexdremov.notate.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HomeViewModelTest {

    private lateinit var app: Application
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = HomeViewModel(app)
    }

    @Test
    fun testInitialStateIsCorrect() {
        // Verify default state
        assertEquals("My Projects", viewModel.title.value)
        assertTrue(viewModel.projects.value.isEmpty())
        assertEquals(null, viewModel.currentProject.value)
        assertEquals(null, viewModel.currentPath.value)
        assertTrue(viewModel.breadcrumbs.value.isEmpty())
    }

    @Test
    fun testNavigateUpAtTopLevelDoesNothing() {
        val isTop = viewModel.isAtTopLevel()
        assertTrue("Should be at top level initially", isTop)
        
        viewModel.navigateUp()
        
        // Should remain at top level
        assertEquals("My Projects", viewModel.title.value)
        assertEquals(null, viewModel.currentProject.value)
    }

    @Test
    fun testTagManagement() {
        val initialTags = viewModel.tags.value
        
        // Add a tag
        viewModel.addTag("Urgent", 0xFFFF0000.toInt())
        
        // Since tags are saved to SharedPreferences via PreferencesManager,
        // and Robolectric provides a working Context, this should update the state.
        val updatedTags = viewModel.tags.value
        assertTrue("Tags list should have grown", updatedTags.size > initialTags.size)
        
        val newTag = updatedTags.find { it.name == "Urgent" }
        assertNotNull("New tag should exist", newTag)
        assertEquals(0xFFFF0000.toInt(), newTag?.color)
    }
}
