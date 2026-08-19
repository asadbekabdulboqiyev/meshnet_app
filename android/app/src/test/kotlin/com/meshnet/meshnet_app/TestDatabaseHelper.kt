package com.meshnet.meshnet_app

import com.meshnet.meshnet_app.storage.MeshDatabase

/**
 * Delegates to [MockDatabaseFactory] (Java) to avoid Kotlin null-safety NPEs
 * on Mockito matcher arguments.
 */
object TestDatabaseHelper {
    fun createMockDatabase(): MeshDatabase = MockDatabaseFactory.createMockDatabase()
}
