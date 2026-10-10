package com.devknow.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devknow.domain.entity.IngestionJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IngestionJobMapper extends BaseMapper<IngestionJob> {

    /**
     * 取出一个待执行任务并加悲观锁。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是本项目最有面试价值的一段 SQL：
     * <ul>
     *   <li>{@code FOR UPDATE} —— 锁定该行，其他 Worker 无法同时取到</li>
     *   <li>{@code SKIP LOCKED} —— 已被其他事务锁定的行直接跳过，不阻塞等待</li>
     * </ul>
     * 两者结合即"多实例安全的任务队列"，且不会因某个慢任务阻塞整个消费循环。
     *
     * <p>命中部分索引 {@code idx_job_pick(status, created_at) WHERE status='PENDING'}。
     *
     * <p><b>必须在外层事务中调用</b> —— 否则方法返回时锁立即释放，失去意义。
     */
    @Select("""
            SELECT * FROM ingestion_job
             WHERE status = 'PENDING'
             ORDER BY created_at
             FOR UPDATE SKIP LOCKED
             LIMIT 1
            """)
    IngestionJob selectNextPendingForUpdate();

    /** 统计某知识源下未完成的任务数，用于阻止重复触发同步。 */
    @Select("""
            SELECT COUNT(1) FROM ingestion_job
             WHERE source_id = #{sourceId}
               AND status IN ('PENDING', 'RUNNING')
            """)
    int countUnfinished(@Param("sourceId") Long sourceId);

    /** 锁超时回收：把卡在 RUNNING 超过 timeout 的任务重新置为 PENDING。 */
    @Select("""
            SELECT * FROM ingestion_job
             WHERE status = 'RUNNING'
               AND locked_at < #{deadline}
            """)
    List<IngestionJob> selectStaleRunning(@Param("deadline") Instant deadline);
}
