package com.charmnight.linkgraph.investigation.resolving

import com.charmnight.linkgraph.investigation.application.EvidenceGoal
import com.charmnight.linkgraph.investigation.application.ResolutionOutcome
import com.intellij.openapi.application.ReadAction

/**
 * 在 IntelliJ ReadAction 内执行证据解析的抽象基类。
 *
 * PSI 访问必须持有读锁，本类把"取得读锁 + 调用具体解析逻辑"封装为模板方法，
 * 让子类只需实现 [resolveInReadAction]，不必每次手写 ReadAction 包装。
 */
abstract class ReadActionEvidenceResolver : EvidenceResolver {
    /**
     * 模板方法：在 ReadAction 内调用子类实现。
     * final 修饰避免子类绕过读锁直接重写 [resolve]。
     */
    final override fun resolve(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome {
        return ReadAction.compute<ResolutionOutcome, RuntimeException> {
            resolveInReadAction(goal, context)
        }
    }

    /**
     * 子类实现的实际解析逻辑，已经在 ReadAction 上下文中调用，可直接访问 PSI。
     *
     * @param goal 证据目标，描述需要解析什么
     * @param context 调查上下文，提供项目与已有证据
     * @return 解析结果
     */
    protected abstract fun resolveInReadAction(
        goal: EvidenceGoal,
        context: InvestigationContext,
    ): ResolutionOutcome
}
