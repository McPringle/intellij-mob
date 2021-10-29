/*
 * Copyright 2020-2021 Koji Hasegawa. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.nowsprinting.intellij_mob.action.start

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.nowsprinting.intellij_mob.MobBundle
import com.nowsprinting.intellij_mob.action.share.ShareAction
import com.nowsprinting.intellij_mob.config.MobProjectSettings
import com.nowsprinting.intellij_mob.config.validateForStartTask
import com.nowsprinting.intellij_mob.git.*
import com.nowsprinting.intellij_mob.timer.TimerService
import com.nowsprinting.intellij_mob.util.notify
import com.nowsprinting.intellij_mob.util.status
import git4idea.repo.GitRepository

class StartTask(val settings: MobProjectSettings, val e: AnActionEvent, project: Project, title: String) :
    Backgroundable(project, title) {
    private val logger = Logger.getInstance(javaClass)
    private val notifyContents = mutableListOf<String>()
    private var completed = false
    private lateinit var repository: GitRepository

    override fun run(indicator: ProgressIndicator) {
        val fractionPerCommandSection = 1.0 / 5
        indicator.isIndeterminate = false
        indicator.fraction = 0.0
        logger.debug(String.format(MobBundle.message("mob.notify_content.begin"), title))

        repository = when (val result = getGitRepository(project)) {
            is GitRepositoryResult.Success -> {
                result.repository
            }
            is GitRepositoryResult.Failure -> {
                logger.warn(result.reason)
                notifyContents.add(String.format(MobBundle.message("mob.notify_content.failure"), result.reason))
                return
            }
        }

        val (validSettings, reasonInvalidSettings) = settings.validateForStartTask()
        if (!validSettings) {
            val format = MobBundle.message("mob.start.error.precondition")
            val message = String.format(format, reasonInvalidSettings)
            logger.warn(message)
            notifyContents.add(String.format(MobBundle.message("mob.notify_content.failure"), message))
            return
        }

        val (validRepository, reasonInvalidRepository) = repository.validateForStart(settings)
        if (!validRepository) {
            val format = MobBundle.message("mob.start.error.precondition")
            val message = String.format(format, reasonInvalidRepository)
            logger.warn(message)
            notifyContents.add(String.format(MobBundle.message("mob.notify_content.failure"), message))
            return
        }
        indicator.fraction += fractionPerCommandSection

        if (!fetch(repository, notifyContents)) {
            return
        }
        indicator.fraction += fractionPerCommandSection

        if (!pull(repository, notifyContents)) {
            return
        }
        indicator.fraction += fractionPerCommandSection

        val hasMobProgrammingBranch = repository.hasMobProgrammingBranch(settings)
        val hasMobProgrammingBranchOrigin = repository.hasMobProgrammingBranchOrigin(settings)
        if (hasMobProgrammingBranch && hasMobProgrammingBranchOrigin) {
            if (!rejoiningMobSession()) {
                return
            }
        } else if (!hasMobProgrammingBranch && !hasMobProgrammingBranchOrigin) {
            if (!createWipBranchFromBaseBranch()) {
                return
            }
        } else if (!hasMobProgrammingBranch && hasMobProgrammingBranchOrigin) {
            if (!joiningMobSession()) {
                return
            }
        } else {
            if (!purgingLocalBranchAndStartNewWipBranchFromBaseBranch()) {
                return
            }
        }
        indicator.fraction += fractionPerCommandSection

        startTimer()
        startWithShare()

        notifyContents.add(status(repository, settings))

        indicator.fraction = 1.0
        completed = true
    }

    override fun onFinished() {
        if (completed) {
            logger.debug(String.format(MobBundle.message("mob.notify_content.success"), title))
            notify(
                project = project,
                title = MobBundle.message("mob.start.task_successful"),
                contents = notifyContents,
                type = NotificationType.INFORMATION
            )
        } else {
            logger.debug(String.format(MobBundle.message("mob.notify_content.failure"), title))
            notify(
                project = project,
                title = MobBundle.message("mob.start.task_failure"),
                contents = notifyContents,
                type = NotificationType.ERROR
            )
        }
        VirtualFileManager.getInstance().asyncRefresh {
            logger.debug(MobBundle.message("mob.logging.refresh"))
        }
    }

    private fun rejoiningMobSession(): Boolean {
        val message = MobBundle.message("mob.start.rejoining_mob_session")
        logger.debug(message)
        notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))

        if (!repository.isMobProgramming(settings)) {
            if (!deleteBranch(settings.wipBranch, repository, notifyContents)) {    // TODO: check if unmerged commits
                return false
            }
            if (!checkout(settings.wipBranch, repository, notifyContents)) {
                return false
            }
            if (!setUpstreamToRemoteBranch(settings.remoteName, settings.wipBranch, repository, notifyContents)) {
                return false
            }
        }
        return true
    }

    private fun createWipBranchFromBaseBranch(): Boolean {
        val messageFormat = MobBundle.message("mob.start.create_wip_branch_from_base_branch")
        val message = String.format(messageFormat, settings.wipBranch, settings.baseBranch)
        logger.debug(message)
        notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))

        if (!checkout(settings.baseBranch, repository, notifyContents)) {
            return false
        }
        if (!mergeFastForward(settings.remoteName, settings.baseBranch, repository, notifyContents)) {
            return false
        }
        if (!createBranch(settings.wipBranch, repository, notifyContents)) {
            return false
        }
        if (!checkout(settings.wipBranch, repository, notifyContents)) {
            return false
        }
        if (!push(settings.remoteName, settings.wipBranch, repository, notifyContents)) {
            return false
        }
        return true
    }

    private fun joiningMobSession(): Boolean {
        val message = MobBundle.message("mob.start.joining_mob_session")
        logger.debug(message)
        notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))

        if (!checkout(settings.wipBranch, repository, notifyContents)) {
            return false
        }
        if (!setUpstreamToRemoteBranch(settings.remoteName, settings.wipBranch, repository, notifyContents)) {
            return false
        }
        return true
    }

    private fun purgingLocalBranchAndStartNewWipBranchFromBaseBranch(): Boolean {
        val messageFormat = MobBundle.message("mob.start.purging_local_branch_and_start_new_wip_branch_from_base")
        val message = String.format(messageFormat, settings.wipBranch, settings.baseBranch)
        logger.debug(message)
        notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))

        if (!deleteBranch(settings.wipBranch, repository, notifyContents)) {    // TODO: check if unmerged commits
            return false
        }
        if (!checkout(settings.baseBranch, repository, notifyContents)) {
            return false
        }
        if (!mergeFastForward(settings.remoteName, settings.baseBranch, repository, notifyContents)) {
            return false
        }
        if (!createBranch(settings.wipBranch, repository, notifyContents)) {
            return false
        }
        if (!checkout(settings.wipBranch, repository, notifyContents)) {
            return false
        }
        if (!push(settings.remoteName, settings.wipBranch, repository, notifyContents)) {
            return false
        }
        return true
    }

    private fun startTimer() {
        val timer = TimerService.getInstance(project)
        if (timer != null && !timer.isRunning()) {
            timer.start(minutes = settings.timerMinutes, sound = settings.timerSound)
            val message = MobBundle.message("mob.timer.start_successful")
            notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))
        } else {
            val message = MobBundle.message("mob.timer.start_failure")
            logger.warn(message)
            notifyContents.add(String.format(MobBundle.message("mob.notify_content.warning"), message))
        }
    }

    private fun startWithShare() {
        if (settings.startWithShare) {
            ShareAction().actionPerformed(e)
            val message = MobBundle.message("mob.screenshare.share_successful")
            notifyContents.add(String.format(MobBundle.message("mob.notify_content.notify"), message))
        }
    }
}