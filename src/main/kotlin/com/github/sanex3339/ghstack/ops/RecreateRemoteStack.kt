package com.github.sanex3339.ghstack.ops

import com.github.sanex3339.ghstack.cli.StackCli
import com.github.sanex3339.ghstack.model.StackUi
import java.nio.file.Path

/**
 * Prepares a stack whose order changed for `gh stack submit --auto` to create anew: unstack it on
 * GitHub, then clear the local stack ID (re-init) so submit may retarget PR bases and create the stack.
 */
class RecreateRemoteStack(private val cli: StackCli, private val gitDir: Path) {
    fun run(remote: RemoteStacks, remoteNumber: Int, stack: StackUi, returnTo: String) {
        remote.unstack(remoteNumber)
        if (stack.id != null) {
            LocalStackReinit(cli, gitDir).run(stack.trunk, stack.activeBranches.map { it.name }, checkoutAfter = returnTo)
        }
    }
}
