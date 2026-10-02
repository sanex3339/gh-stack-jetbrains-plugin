package com.github.sanex3339.ghstack.testutil

import com.github.sanex3339.ghstack.model.BranchStatus
import com.github.sanex3339.ghstack.model.BranchUi
import com.github.sanex3339.ghstack.model.PrState
import com.github.sanex3339.ghstack.model.PrUi
import com.github.sanex3339.ghstack.model.StackUi

object Stacks {
    /** Builds a current stack; [branches] bottom → top. Every branch except NOT_SUBMITTED gets PR 100, 101, … */
    fun stack(
        trunk: String,
        vararg branches: Pair<String, BranchStatus>,
        current: String? = null,
        number: Int? = null,
        id: String? = null,
    ): StackUi {
        var nextPr = 100
        return StackUi(
            id = id,
            number = number,
            trunk = trunk,
            branches = branches.map { (name, status) ->
                val pr = if (status == BranchStatus.NOT_SUBMITTED) null else {
                    val n = nextPr++
                    PrUi(n, "https://github.com/octo/app/pull/$n", if (status == BranchStatus.MERGED) PrState.MERGED else PrState.OPEN)
                }
                BranchUi(name, name == current, status, pr)
            },
            isCurrent = true,
        )
    }
}
