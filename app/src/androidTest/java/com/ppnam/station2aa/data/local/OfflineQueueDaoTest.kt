package com.ppnam.station2aa.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OfflineQueueDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: OfflineQueueDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.offlineQueueDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun insertAndGetPending() = runTest {
        val entity = OfflineQueueEntity("id1", "complete-premix", "{}", System.currentTimeMillis())
        dao.insert(entity)
        val pending = dao.getPending()
        assertEquals(1, pending.size)
        assertEquals("id1", pending[0].id)
    }

    @Test
    fun markSentRemovesFromPending() = runTest {
        dao.insert(OfflineQueueEntity("id2", "allocate-rajoo", "{}", System.currentTimeMillis()))
        dao.markSent("id2")
        assertEquals(0, dao.getPending().size)
    }

    @Test
    fun incrementRetryAndMarkFailed() = runTest {
        dao.insert(OfflineQueueEntity("id3", "recover-rfid-read", "{}", System.currentTimeMillis()))
        repeat(10) { dao.incrementRetry("id3") }
        dao.markFailed("id3")
        val failed = dao.getFailedAsFlow().first()
        assertEquals(1, failed.size)
        assertEquals(10, failed[0].retryCount)
    }

    @Test
    fun pendingCountFlow() = runTest {
        assertEquals(0, dao.pendingCount().first())
        dao.insert(OfflineQueueEntity("id4", "complete-premix", "{}", System.currentTimeMillis()))
        assertEquals(1, dao.pendingCount().first())
    }
}
