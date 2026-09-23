package com.example.data.database

import androidx.room.*

@Dao
interface CallLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: CallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<CallLogEntity>)

    @Query("SELECT * FROM call_logs WHERE ownerUid = :ownerUid AND leadId = :leadId ORDER BY callTime DESC LIMIT 50")
    suspend fun getLogsForLead(ownerUid: String, leadId: String): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE ownerUid = :ownerUid ORDER BY callTime DESC")
    suspend fun getAllForOwner(ownerUid: String): List<CallLogEntity>

    @Query("SELECT * FROM call_logs WHERE ownerUid = :ownerUid")
    suspend fun getAllForOwnerUnordered(ownerUid: String): List<CallLogEntity>

    @Query("DELETE FROM call_logs WHERE ownerUid = :ownerUid AND leadId = :leadId")
    suspend fun deleteForLead(ownerUid: String, leadId: String)

    @Query("DELETE FROM call_logs WHERE ownerUid = :ownerUid")
    suspend fun deleteForOwner(ownerUid: String)

    @Query("DELETE FROM call_logs")
    suspend fun clearAll()
}
