package com.wuxianggujun.tinaide.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 项目位置 DAO
 */
@Dao
interface ProjectLocationDao {

    /**
     * 获取所有项目位置（一次性查询）
     */
    @Query("SELECT * FROM project_locations ORDER BY registered DESC")
    suspend fun getAllLocations(): List<ProjectLocationEntity>

    /**
     * 插入或更新项目位置
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: ProjectLocationEntity): Long

    /**
     * 删除项目位置
     */
    @Query("DELETE FROM project_locations WHERE project_id = :projectId")
    suspend fun deleteLocation(projectId: String): Int

    @Query(
        "DELETE FROM project_locations " +
            "WHERE source_root_path = :sourceRootPath AND project_id != :projectId"
    )
    suspend fun deleteOtherLocationsForSourcePath(sourceRootPath: String, projectId: String): Int
}
