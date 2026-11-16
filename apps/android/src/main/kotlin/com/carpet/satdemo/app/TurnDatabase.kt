package com.carpet.satdemo.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.carpet.satdemo.core.ai.ConversationLog
import com.carpet.satdemo.core.ai.ReplySource
import com.carpet.satdemo.core.ai.Turn
import com.carpet.satdemo.core.ai.TurnFrom
import com.carpet.satdemo.core.ai.TurnScene
import com.carpet.satdemo.core.ai.TurnStore
import com.carpet.satdemo.core.protocol.DialogOrigin

/**
 * 对话台账落盘（APP-OPENSOURCE-BASICS.md §2 的第一张表，THIRD-PARTY.md 有记账）。
 *
 * "一条不丢"叙事的最后一环：消息队列 `pending` 早就落了，对话还悬在内存里 ——
 * 杀一次进程，车主就看不见"刚才是不是有人在车那边跟屏说话"。这张表补的就是这个。
 *
 * **只有一张表，一行一条 Turn**。`turnId + from` 是去重键（一轮问答上行下行各一条，
 * `ConversationLog.append` 靠它挡圆屏按两下），主键就用它 —— 重放帧重复入库时直接顶掉。
 * 枚举全按名字存（[TurnFrom] / [ReplySource] / [DialogOrigin] / [TurnScene]），
 * 不用 ordinal：以后往枚举中间插一个值，老记录不会错位变义。
 */
@Entity(tableName = "turns", primaryKeys = ["turnId", "from"])
data class TurnEntity(
    @ColumnInfo(name = "turnId") val turnId: String,
    /** TurnFrom 的名字（Me / Ai），不是 ordinal */
    @ColumnInfo(name = "from") val from: String,
    @ColumnInfo(name = "text") val text: String,
    /** ReplySource 的名字。enum 值删了老记录会读不出来，所以加值不加删值 */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "skill") val skill: String?,
    @ColumnInfo(name = "proactive") val proactive: Boolean,
    /** DialogOrigin 的名字 */
    @ColumnInfo(name = "via") val via: String,
    /** TurnScene 的名字，可空 —— 不归页的句子只在总时间线出现 */
    @ColumnInfo(name = "scene") val scene: String?,
    /** 落库时刻。回灌后排序靠它，不靠自增 id */
    @ColumnInfo(name = "atMs") val atMs: Long,
) {
    fun toTurn(): Turn = Turn(
        from = TurnFrom.valueOf(from),
        text = text,
        source = ReplySource.valueOf(source),
        skill = skill,
        proactive = proactive,
        via = DialogOrigin.valueOf(via),
        turnId = turnId,
        scene = scene?.let { TurnScene.valueOf(it) },
    )

    companion object {
        fun of(t: Turn, atMs: Long) = TurnEntity(
            turnId = t.turnId,
            from = t.from.name,
            text = t.text,
            source = t.source.name,
            skill = t.skill,
            proactive = t.proactive,
            via = t.via.name,
            scene = t.scene?.name,
            atMs = atMs,
        )
    }
}

@Dao
interface TurnDao {
    /** REPLACE：同一帧重放（断线重连、圆屏重发）时顶掉旧行，不叠两条 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(turn: TurnEntity)

    /** 最近 cap 条，按落库时刻升序 —— [ConversationLog] 的 seed 要的就是"老的在前" */
    @Query("SELECT * FROM turns ORDER BY atMs DESC LIMIT :cap")
    fun recent(cap: Int): List<TurnEntity>

    @Query("DELETE FROM turns")
    fun clear()

    @Query("DELETE FROM turns WHERE atMs < :beforeMs")
    fun trim(beforeMs: Long)
}

@Database(entities = [TurnEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun turnDao(): TurnDao

    companion object {
        /** 单例。台账只有一个写方（主 scope 的 ConversationLog），并发不构成问题 */
        @Volatile
        private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, ChatDatabase::class.java, "chat.db")
                // ConversationLog 只从主 scope 动（见它的线程注释），写一条 <1ms，
                // 同步写换"内存和库顺序天然一致"。不放开这个，Room 会在主线程上直接抛
                .allowMainThreadQueries()
                // 现阶段没有迁移故事：结构改了就重建。台账是记录不是资产，丢了可以重演
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

/**
 * [TurnStore] 的 Room 实现。**调用方（ConversationLog）在主线程上同步调** ——
 * 一句 ≤30 字的答话，Room 写一条 <1ms，直接写比"切线程再等回来"诚实：
 * 内存和库的顺序天然一致，不会出现"列表里第三条还没落库进程就被杀了"的缝。
 * Room 允许主线程同步调用（allowMainThreadQueries），量级就是一张小表。
 */
class RoomTurnStore(context: Context) : TurnStore {
    private val dao = ChatDatabase.get(context).turnDao()

    override fun put(turn: Turn) {
        dao.put(TurnEntity.of(turn, System.currentTimeMillis()))
    }

    override fun loadRecent(cap: Int): List<Turn> = dao.recent(cap).map { it.toTurn() }

    override fun clear() = dao.clear()
}

/**
 * 给 [ConversationLog] 用的装配辅助：先同步回灌最近 [cap] 条，再带着 store 构造。
 * 宿主在 Application 装配里调一次 —— StateFlow 一初始化就有历史，
 * 首页不会先闪空白再"历史回来"。顺带把 7 天前的旧条目清掉：cap=200 的内存只装最近一轮，
 * 库里也只留一周的对话，演示机跑几个月不至于把内部存储写满。
 */
fun conversationLogWithDb(context: Context, guard: (String) -> String, log: (String) -> Unit): ConversationLog {
    val store = RoomTurnStore(context)
    // 回灌用的是过闸前的原文吗？—— 是。append 时落库的就是过闸后的文本，
    // 读回来不必再过一遍闸：当时没拦住的话如今也拦不住了，再拦一遍只会改历史
    val seed = runCatching { store.loadRecent(ConversationLogCap) }.getOrElse { emptyList() }
    // 台账只留一周。trim 在启动时做一次就够：App 不是常驻写入方，一天几百条封顶
    runCatching {
        ChatDatabase.get(context).turnDao().trim(System.currentTimeMillis() - RETAIN_MS)
    }
    return ConversationLog(
        cap = ConversationLogCap,
        guard = guard,
        log = log,
        store = store,
        seed = seed,
    )
}

/** 跟 [ConversationLog] 默认的内存上限一致：库里多留没有意义，回灌也只读这么多 */
const val ConversationLogCap = 200

/** 台账在库里留一周。超过的启动时清一次 */
const val RETAIN_MS: Long = 7L * 24 * 60 * 60 * 1000
