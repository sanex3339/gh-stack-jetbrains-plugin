package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.planning.InsertPlan
import java.nio.file.Path

/** Inserts a branch into the stack locally and checks it out. Publishing happens later via Submit. */
class InsertBranchWorkflow(private val cli: StackCli, private val gitDir: Path) {
    fun run(plan: InsertPlan, currentBranch: String?) {
        cli.git("check-ref-format", "--branch", plan.newBranch).orAbort("Validating the branch name")
        when (plan) {
            is InsertPlan.AddOnTop -> {
                if (currentBranch != plan.top) cli.git("checkout", plan.top).orAbort("Switching to ${plan.top}")
                cli.stack("add", plan.newBranch).orAbort("Adding ${plan.newBranch}")
            }
            is InsertPlan.Reinit -> {
                // `unstack --local` acts on the stack of the checked-out branch.
                if (currentBranch == null || currentBranch !in plan.newOrder) {
                    cli.git("checkout", plan.target).orAbort("Switching to ${plan.target}")
                }
                LocalStackReinit(cli, gitDir).run(
                    trunk = plan.trunk,
                    order = plan.newOrder,
                    checkoutAfter = plan.newBranch,
                    createBranch = plan.newBranch to plan.parent,
                )
            }
        }
    }
}
