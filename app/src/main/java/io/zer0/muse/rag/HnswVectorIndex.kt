package io.zer0.muse.rag

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.55: 向量索引统一接口。
 *
 * 用于抽象"向量如何存储与检索",支持后续切换不同 ANN 算法
 * (当前实现:[HnswVectorIndex];暴力遍历复用现有 [VectorSearchService])。
 */
interface VectorIndex {
    /** 添加向量(同 id 已存在则覆盖)。 */
    fun add(id: String, vector: FloatArray)

    /** 检索 k 个最近邻(按相似度降序返回)。 */
    fun search(query: FloatArray, k: Int): List<SearchResult>

    /** 删除指定 id(不存在则忽略)。 */
    fun remove(id: String)

    /** 当前索引中向量数。 */
    val size: Int

    /** 持久化到文件(覆盖现有内容)。 */
    fun save(file: File)

    /** 从文件加载(替换当前索引内容)。 */
    fun load(file: File)
}

/**
 * v1.55: 向量检索结果。
 *
 * @property id chunk 标识(对应 [io.zer0.muse.data.knowledge.KnowledgeChunkEntity.id])
 * @property score 余弦相似度(范围 [-1,1],越大越相似;通常 [0,1])
 * @property distance 余弦距离(范围 [0,2],越小越相似;= 1 - [score])
 */
data class SearchResult(
    val id: String,
    val score: Float,
    val distance: Float,
)

/**
 * v1.55: HNSW(Hierarchical Navigable Small World)近似最近邻索引。
 *
 * 纯 Kotlin 实现,不引入第三方库(避免依赖膨胀)。
 * 算法参考:Malkov & Yashunin 2016 "Efficient and robust approximate nearest
 * neighbor search using Hierarchical Navigable Small World graphs"。
 *
 * 参数:
 *  - [M]:每层最大连接数(层 0 上限 = 2*M,其他层 = M)
 *  - [efConstruction]:构建时候选集大小(越大召回越高,构建越慢)
 *  - [efSearch]:查询时候选集大小(越大召回越高,查询越慢)
 *  - [maxLayer]:当前最大层级(运行时维护,初始 -1)
 *
 * 距离度量:余弦距离 = 1 - 余弦相似度(范围 [0,2],越小越相似)。
 * 不要求向量预先 L2 归一化,内部对每对向量独立计算 dot / norm。
 *
 * 线程安全:[ReentrantReadWriteLock] — 读操作(search/size/save)并行,写操作(add/remove/load)独占。
 *
 * 持久化:二进制格式(magic header + version + 参数 + node count + 各节点 layer/connections/vectors)。
 * 删除策略:软删除(标记 deleted=true),保存时压缩剔除;不重用 index 槽位以避免大范围邻居重映射。
 *
 * @param M 每层最大连接数,默认 16
 * @param efConstruction 构建时候选集大小,默认 200
 * @param efSearch 查询时候选集大小,默认 50
 * @param seed 随机种子(可复现构建过程)
 */
class HnswVectorIndex(
    private val M: Int = DEFAULT_M,
    private val efConstruction: Int = DEFAULT_EF_CONSTRUCTION,
    private val efSearch: Int = DEFAULT_EF_SEARCH,
    private val seed: Long = DEFAULT_SEED,
) : VectorIndex {

    /** 内部节点:向量 + 层级 + 各层邻居(用内部 index 引用)。 */
    private class Node(
        val id: String,
        val vector: FloatArray,
        val level: Int,
        val connections: Array<MutableList<Int>>,
        @Volatile var deleted: Boolean = false,
    )

    private val nodes = mutableListOf<Node>()        // 内部 index → Node
    private val idToIndex = HashMap<String, Int>()   // 外部 id → 内部 index
    @Volatile private var entryPoint: Int = -1       // 入口点 index
    @Volatile private var maxLayer: Int = -1         // 当前最大层级
    private val rng = Random(seed)
    private val lock = ReentrantReadWriteLock()
    /** mL = 1 / ln(M),用于按指数分布生成节点层级(论文公式)。 */
    private val mL: Double = 1.0 / ln(M.toDouble())

    override val size: Int
        get() = lock.read { idToIndex.size }

    override fun add(id: String, vector: FloatArray) = lock.write {
        // 同 id 已存在 → 软删除旧节点(覆盖语义)
        idToIndex.remove(id)?.let { softDeleteInternal(it) }

        val level = randomLevel()
        val node = Node(
            id = id,
            vector = vector.copyOf(),
            level = level,
            connections = Array(level + 1) { mutableListOf() },
        )
        val newIndex = nodes.size
        nodes.add(node)
        idToIndex[id] = newIndex

        // 首个节点 → 设为入口点
        if (entryPoint < 0 || maxLayer < 0) {
            entryPoint = newIndex
            maxLayer = level
            return@write
        }

        // 1. 顶层到 level+1:贪心找最近入口(ef=1)
        var cur = entryPoint
        for (layer in maxLayer downTo level + 1) {
            cur = searchLayer(vector, listOf(cur), layer, ef = 1)
                .firstOrNull()?.second ?: cur
        }

        // 2. min(level, maxLayer) → 0:搜索 + 连接
        val startLayer = minOf(level, maxLayer)
        for (layer in startLayer downTo 0) {
            val candidates = searchLayer(vector, listOf(cur), layer, efConstruction)
                .filter { it.second != newIndex }  // 排除自己
            val selected = selectNeighborsHeuristic(vector, candidates, M)
            for ((_, neighborIdx) in selected) {
                if (neighborIdx == newIndex) continue
                node.connections[layer].add(neighborIdx)
                val neighbor = nodes[neighborIdx]
                neighbor.connections[layer].add(newIndex)
                // 邻居连接数超限 → 剪枝(Mmax0 = 2*M 在层 0,Mmax = M 在其他层)
                val mMax = if (layer == 0) M * 2 else M
                if (neighbor.connections[layer].size > mMax) {
                    val neighborCandidates = neighbor.connections[layer]
                        .map { idx -> distance(neighbor.vector, nodes[idx].vector) to idx }
                    val pruned = selectNeighborsHeuristic(neighbor.vector, neighborCandidates, mMax)
                    neighbor.connections[layer].clear()
                    neighbor.connections[layer].addAll(pruned.map { it.second })
                }
            }
            // 下一层起点:用最近的候选
            cur = candidates.firstOrNull()?.second ?: cur
        }

        // 3. 若新节点层级 > maxLayer,更新入口点
        if (level > maxLayer) {
            entryPoint = newIndex
            maxLayer = level
        }
    }

    override fun search(query: FloatArray, k: Int): List<SearchResult> = lock.read {
        if (entryPoint < 0 || nodes.isEmpty() || k <= 0) return@read emptyList()
        // 顶层到 1:贪心 1-NN
        var cur = entryPoint
        for (layer in maxLayer downTo 1) {
            cur = searchLayer(query, listOf(cur), layer, ef = 1)
                .firstOrNull()?.second ?: cur
        }
        // 层 0:ef 搜索
        val ef = maxOf(efSearch, k)
        val candidates = searchLayer(query, listOf(cur), 0, ef)
        return@read candidates
            .take(k)
            .map { (dist, idx) ->
                val node = nodes[idx]
                SearchResult(id = node.id, score = 1f - dist, distance = dist)
            }
    }

    override fun remove(id: String) = lock.write {
        val idx = idToIndex.remove(id) ?: return@write
        softDeleteInternal(idx)
    }

    /**
     * 写锁内调用:软删除指定 index 的节点。
     *
     * 实现:标记 deleted=true + 从所有邻居的连接中移除自己;
     * 若入口点被删,选择剩余节点中 level 最高的作为新入口。
     * 不物理移除节点(避免 index 错位导致大规模邻居重映射)。
     */
    private fun softDeleteInternal(removedIdx: Int) {
        if (removedIdx !in nodes.indices) return
        val removed = nodes[removedIdx]
        removed.deleted = true
        // 从所有邻居的连接中移除自己
        for (layer in 0..removed.level) {
            for (neighborIdx in removed.connections[layer].toList()) {
                nodes.getOrNull(neighborIdx)?.connections?.getOrNull(layer)?.remove(removedIdx)
            }
        }
        // 入口点被删 → 找剩余未删除节点中 level 最高的
        if (entryPoint == removedIdx) {
            var newEntry = -1
            var newMaxLayer = -1
            for (i in nodes.indices) {
                val n = nodes[i]
                if (n.deleted) continue
                if (n.level > newMaxLayer) {
                    newMaxLayer = n.level
                    newEntry = i
                }
            }
            entryPoint = newEntry
            maxLayer = newMaxLayer
        }
    }

    /**
     * 在指定层执行搜索(论文 Algorithm 2)。
     *
     * @param query 查询向量
     * @param entryPoints 入口点 index 列表
     * @param layer 目标层
     * @param ef 动态候选集大小
     * @return 候选列表(distance, index),按距离升序
     */
    private fun searchLayer(
        query: FloatArray,
        entryPoints: List<Int>,
        layer: Int,
        ef: Int,
    ): List<Pair<Float, Int>> {
        if (entryPoints.isEmpty()) return emptyList()
        val visited = HashSet<Int>()
        // candidates: min-heap by distance (smaller = closer = dequeued first)
        val candidates = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        // W: max-heap by distance (larger = farther = polled first when pruning)
        val W = PriorityQueue<Pair<Float, Int>>(compareByDescending { it.first })

        for (ep in entryPoints) {
            if (ep !in nodes.indices) continue
            val node = nodes[ep]
            if (node.deleted) continue
            if (ep in visited) continue
            val d = distance(query, node.vector)
            candidates.offer(d to ep)
            W.offer(d to ep)
            visited.add(ep)
        }

        while (candidates.isNotEmpty()) {
            val (cDist, cIdx) = candidates.poll() ?: break
            val fDist = W.peek()?.first ?: Float.MAX_VALUE
            if (cDist > fDist) break
            val cNode = nodes[cIdx]
            val conns = cNode.connections.getOrNull(layer) ?: continue
            for (e in conns) {
                if (e in visited) continue
                visited.add(e)
                if (e !in nodes.indices) continue
                val eNode = nodes[e]
                if (eNode.deleted) continue
                val eDist = distance(query, eNode.vector)
                val fDist2 = W.peek()?.first ?: Float.MAX_VALUE
                if (eDist < fDist2 || W.size < ef) {
                    candidates.offer(eDist to e)
                    W.offer(eDist to e)
                    if (W.size > ef) W.poll()
                }
            }
        }

        return W.toList().sortedBy { it.first }  // 升序:最近的在前
    }

    /**
     * 邻居选择启发式(论文 Algorithm 4)。
     *
     * 规则:从候选中按距离升序依次选入结果;若候选 e 到已选 r 的距离
     * 比 e 到 query 的距离更近,则跳过 e(避免聚集)。
     * 若启发式过滤后不足 m,补满(允许非启发式选入)。
     */
    private fun selectNeighborsHeuristic(
        query: FloatArray,
        candidates: List<Pair<Float, Int>>,
        m: Int,
    ): List<Pair<Float, Int>> {
        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedBy { it.first }  // 升序:距离 query 最近的在前
        val result = mutableListOf<Pair<Float, Int>>()
        for ((eDist, eIdx) in sorted) {
            if (result.size >= m) break
            // 启发式:e 到 query 的距离必须比 e 到任意已选 r 的距离更近
            var keep = true
            for ((_, rIdx) in result) {
                val dER = distance(nodes[eIdx].vector, nodes[rIdx].vector)
                if (dER < eDist) {
                    keep = false
                    break
                }
            }
            if (keep) result.add(eDist to eIdx)
        }
        // 启发式过滤后不足 m → 补满
        if (result.size < m) {
            val existing = result.map { it.second }.toSet()
            for ((eDist, eIdx) in sorted) {
                if (result.size >= m) break
                if (eIdx in existing) continue
                result.add(eDist to eIdx)
            }
        }
        return result
    }

    /** 按指数分布生成节点层级:level = floor(-ln(uniform(0,1)) * mL)。 */
    private fun randomLevel(): Int {
        val r = rng.nextDouble()
        // 防止 r=0 导致 log 发散
        val safeR = if (r <= 0.0) 1e-10 else r
        return Math.floor(-Math.log(safeR) * mL).toInt()
    }

    /** 余弦距离 = 1 - 余弦相似度(范围 [0,2])。 */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            na += av * av
            nb += bv * bv
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom == 0f) return 1f
        return 1f - dot / denom
    }

    override fun save(file: File) = lock.read {
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { out ->
            // HEADER
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            out.writeInt(VERSION)
            out.writeInt(M)
            out.writeInt(efConstruction)
            out.writeInt(efSearch)
            out.writeInt(maxLayer)

            // 收集存活节点 + 建立老 index → 新(紧凑)index 映射
            val alive = nodes.mapIndexedNotNull { idx, node ->
                if (!node.deleted) idx to node else null
            }
            val oldToNew = HashMap<Int, Int>()
            alive.forEachIndexed { newIdx, (oldIdx, _) -> oldToNew[oldIdx] = newIdx }

            out.writeInt(oldToNew[entryPoint] ?: -1)
            out.writeInt(alive.size)

            // NODES
            for ((_, node) in alive) {
                val idBytes = node.id.toByteArray(Charsets.UTF_8)
                out.writeInt(idBytes.size)
                out.write(idBytes)
                out.writeInt(node.level)
                out.writeInt(node.vector.size)
                for (v in node.vector) out.writeFloat(v)
                out.writeInt(node.level + 1)
                for (layer in 0..node.level) {
                    // 过滤掉指向已删除节点的连接(老 index 不在 oldToNew 中)
                    val conns = node.connections[layer].mapNotNull { oldToNew[it] }
                    out.writeInt(conns.size)
                    for (c in conns) out.writeInt(c)
                }
            }
        }
    }

    override fun load(file: File) = lock.write {
        if (!file.exists()) return@write
        DataInputStream(FileInputStream(file)).use { inp ->
            // HEADER
            val magicBuf = ByteArray(MAGIC.length)
            inp.readFully(magicBuf)
            if (String(magicBuf, Charsets.US_ASCII) != MAGIC) {
                return@write  // magic 不匹配,跳过加载
            }
            val version = inp.readInt()
            if (version != VERSION) {
                return@write  // 版本不兼容,跳过加载
            }
            // 读参数(忽略,沿用构造时设定的值)
            inp.readInt()  // M
            inp.readInt()  // efConstruction
            inp.readInt()  // efSearch
            maxLayer = inp.readInt()
            entryPoint = inp.readInt()
            val nodeCount = inp.readInt()

            // 重建 nodes
            nodes.clear()
            idToIndex.clear()
            for (i in 0 until nodeCount) {
                val idLen = inp.readInt()
                val idBytes = ByteArray(idLen)
                inp.readFully(idBytes)
                val id = String(idBytes, Charsets.UTF_8)
                val level = inp.readInt()
                val dim = inp.readInt()
                val vec = FloatArray(dim)
                for (j in 0 until dim) vec[j] = inp.readFloat()
                val layerCount = inp.readInt()
                val conns = Array(layerCount) { mutableListOf<Int>() }
                for (layer in 0 until layerCount) {
                    val cc = inp.readInt()
                    for (j in 0 until cc) conns[layer].add(inp.readInt())
                }
                nodes.add(Node(id, vec, level, conns))
                idToIndex[id] = i
            }
        }
    }

    companion object {
        /** 默认 M:每层最大连接数。M=16 在大多数场景下召回与构建速度平衡良好。 */
        const val DEFAULT_M = 16
        /** 默认 efConstruction:构建时候选集大小。200 是论文推荐值。 */
        const val DEFAULT_EF_CONSTRUCTION = 200
        /** 默认 efSearch:查询时候选集大小。50 兼顾召回(>95%)与速度。 */
        const val DEFAULT_EF_SEARCH = 50
        /** 默认随机种子(可复现构建过程)。 */
        const val DEFAULT_SEED = 42L
        /** 持久化文件 magic header(6 字节 ASCII)。 */
        private const val MAGIC = "HNSW01"
        /** 持久化文件版本。 */
        private const val VERSION = 1
    }
}
