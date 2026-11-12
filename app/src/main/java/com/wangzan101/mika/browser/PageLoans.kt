package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 浏览器页上看着的那一页借给任务(用户在这一页上问如愿):用户一问,先「答应借」给那个对话([offer]);
 * 那个对话的任务开跑时来取([claim]),取到后这个标签就是任务的当前网页标签,任务一侧接上([bind])。
 * 同一时间只借出一份(看得见的页面只有一个)。生死仍归浏览器页:
 * - 任务结束([end]):还回来,页面留在用户眼前;
 * - 看得见的标签换了([swapped],页面开了或关了弹出的窗口):借的跟着换成看得见的那个;
 * - 用户关掉页面([closing]):页面交给任务接着用(收下了浏览器页就不再销毁它),收不下才由浏览器页关掉;
 * - 用户摸了页面([touched]):告诉任务一声(任务据此暂停)。
 * 任务那一侧见 [Borrower]。[T] 是标签(界面里是 WebTab),测试换成别的。只在主线程调用。
 */
class PageLoans<T : Any> {
    /** 借了标签的任务一侧要做的事。 */
    interface Borrower<T : Any> {
        /** 当成当前标签用(借进来)。借不进来返回 false。 */
        fun take(tab: T): Boolean
        /** 还回来(不再用它)。 */
        fun giveBack(tab: T)
        /** 借的换成了 [next]。 */
        fun swap(prev: T, next: T)
        /** 用户关掉了页面:收下 [tab] 当自己的标签接着用;收不下返回 false。 */
        fun adopt(tab: T): Boolean
        /** 用户亲手摸了这一页。 */
        fun touched()
    }

    /** 借出去的一份:对话 [conversationId] 里运行 [runKey] 的任务借着 [tab];[touches] 是借出期间用户摸了几下。 */
    class Loan<T : Any> internal constructor(val conversationId: String, val runKey: String, tab: T) {
        var tab: T = tab
            internal set
        var touches: Int = 0
            internal set
        internal var borrower: Borrower<T>? = null
    }

    private var offered: String? = null
    private var loan: Loan<T>? = null

    private val _lentTo = MutableStateFlow<String?>(null)
    /** 此刻借着页面的那个对话;没借出去为 null。 */
    val lentTo: StateFlow<String?> = _lentTo.asStateFlow()

    /** 此刻借出去的那一份;没有为 null。 */
    val current: Loan<T>? get() = loan

    /** 用户在页面上问了,答应借给对话 [conversationId] 的下一个任务(替换先前答应的)。 */
    fun offer(conversationId: String) {
        offered = conversationId
    }

    /** 答应过、还没被取走的那个对话。 */
    val offeredTo: String? get() = offered

    /** 页面没了,答应过的作废。 */
    fun withdraw() {
        offered = null
    }

    /**
     * 对话 [conversationId] 的任务 [runKey] 开跑时来取:答应过给这个对话、此刻有看得见的页面 [tab]、又没借给别人时借出,
     * 答应的随之用掉;否则为 null。
     */
    fun claim(conversationId: String, runKey: String, tab: T?): Loan<T>? {
        if (offered != conversationId || tab == null || loan != null) return null
        offered = null
        return Loan(conversationId, runKey, tab).also {
            loan = it
            _lentTo.value = conversationId
        }
    }

    /**
     * 任务一侧准备好了:接上 [borrower] 并把当前的标签借进去。借出的已经收回(页面在这之前关了)、或借不进去时返回 false,
     * 借不进去的这一份随之作废。
     */
    fun bind(l: Loan<T>, borrower: Borrower<T>): Boolean {
        if (loan !== l) return false
        if (!borrower.take(l.tab)) {
            clear()
            return false
        }
        l.borrower = borrower
        return true
    }

    /** 任务 [runKey] 结束:借的还回来。不是它借的不动。 */
    fun end(runKey: String) {
        val l = loan?.takeIf { it.runKey == runKey } ?: return
        clear()
        l.borrower?.giveBack(l.tab)
    }

    /** 看得见的标签从 [prev] 换成了 [next]:借的是 [prev] 时跟着换。 */
    fun swapped(prev: T, next: T) {
        val l = loan?.takeIf { it.tab == prev } ?: return
        l.tab = next
        l.borrower?.swap(prev, next)
    }

    /**
     * 浏览器页要关掉标签 [tab]:借着它的任务收下了就返回 true(调用方不再销毁它,也不再管它),否则 false。
     * 不论收没收下,这一份都结束了;答应过、还没取走的也作废。
     */
    fun closing(tab: T): Boolean {
        offered = null
        val l = loan?.takeIf { it.tab == tab } ?: return false
        clear()
        val b = l.borrower ?: return false
        if (b.adopt(tab)) return true
        b.giveBack(tab)
        return false
    }

    /** 用户亲手摸了看得见的标签 [tab]:借着它的任务知道一声。 */
    fun touched(tab: T) {
        val l = loan?.takeIf { it.tab == tab } ?: return
        l.touches++
        l.borrower?.touched()
    }

    private fun clear() {
        loan = null
        _lentTo.value = null
    }
}
