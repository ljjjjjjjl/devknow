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

    /**
     * 熔断器计数：统计"自上次成功以来"累积了多少个死信的 EMBED 任务。
     *
     * <p><b>为什么需要它</b>：调度器只判断"有没有 PENDING/RUNNING 的 EMBED 任务"
     * 是挡不住死信循环的 —— 任务失败进 DEAD 后就不再属于 PENDING/RUNNING，
     * 调度器下一轮会再创建一个，又失败又进 DEAD，死信表无限增长
     * （API key 失效时一天能攒 24 个，一个月 720 个）。
     *
     * <p>本查询把"永久失败"这个无法从系统内部识别的东西，转化成一个<b>有界的状态</b>：
     * 累积到阈值就停止创建，等人工处理 —— 修好配置后删掉死信行即自动复位。
     *
     * <p>{@code '-infinity'::timestamptz} 处理"从未成功过"的情况：
     * 此时 COALESCE 取负无穷，所有历史死信都计入。
     */
    @Select("""
            SELECT COUNT(1) FROM ingestion_job
             WHERE job_type = 'EMBED'
               AND status = 'DEAD'
               AND created_at > COALESCE(
                     (SELECT max(finished_at) FROM ingestion_job
                       WHERE job_type = 'EMBED' AND status = 'SUCCESS'),
                     '-infinity'::timestamptz)
            """)
    int countDeadEmbedJobsSinceLastSuccess();
}
