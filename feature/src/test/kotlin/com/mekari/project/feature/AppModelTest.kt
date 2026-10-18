package com.mekari.project.feature

import app.cash.turbine.test
import com.mekari.project.core.ServerConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppModelTest {
    @Test
    fun greetingFollowsTheServer() = runTest {
        val model = AppModel()
        model.greeting.test {
            assertEquals("Mekari Project", awaitItem())
            model.connect(ServerConfiguration("https://project.mekari.io"))
            assertEquals("Mekari Project · https://project.mekari.io", awaitItem())
        }
    }
}
