/*
 * Copyright (C) 2023 Kevin Buzeau
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.feature.tutorial.domain

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Log

import com.buzbuz.smartautoclicker.core.domain.Repository
import com.buzbuz.smartautoclicker.core.domain.model.DATABASE_ID_INSERTION
import com.buzbuz.smartautoclicker.core.domain.model.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.OR
import com.buzbuz.smartautoclicker.core.domain.model.scenario.Scenario
import com.buzbuz.smartautoclicker.core.processing.domain.DetectionRepository
import com.buzbuz.smartautoclicker.feature.tutorial.data.TutorialDataSource
import com.buzbuz.smartautoclicker.feature.tutorial.data.TutorialEngine
import com.buzbuz.smartautoclicker.feature.tutorial.data.getTutorialPreferences
import com.buzbuz.smartautoclicker.feature.tutorial.data.isFirstTimePopupAlreadyShown
import com.buzbuz.smartautoclicker.feature.tutorial.data.putFirstTimePopupAlreadyShown
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.Tutorial
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.TutorialStep
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.game.TutorialGame
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.game.TutorialGameTargetType
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.game.toDomain
import com.buzbuz.smartautoclicker.feature.tutorial.domain.model.toDomain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TutorialRepository private constructor(
    context: Context,
    private val dataSource: TutorialDataSource,
) {

    companion object {

        /** Singleton preventing multiple instances of the TutorialRepository at the same time. */
        @Volatile
        private var INSTANCE: TutorialRepository? = null

        /**
         * Get the TutorialRepository singleton, or instantiates it if it wasn't yet.
         *
         * @return the TutorialRepository singleton.
         */
        fun getTutorialRepository(context: Context): TutorialRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TutorialRepository(context, TutorialDataSource)
                INSTANCE = instance
                instance
            }
        }
    }

    private val coroutineScopeMain: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val scenarioRepository: Repository = Repository.getRepository(context)
    private val detectionRepository: DetectionRepository =  DetectionRepository.getDetectionRepository(context)

    private val sharedPrefs: SharedPreferences = context.getTutorialPreferences()

    private val tutorialEngine: TutorialEngine = TutorialEngine(context, coroutineScopeMain)

    /**
     * The identifier of the user scenario when he enters the tutorial mode.
     * Kept to be restored once he quit the tutorial.
     */
    private var scenarioId: Identifier? = null
    private var allStepsCompleted: Boolean = false

    private val activeTutorialIndex: MutableStateFlow<Int?> = MutableStateFlow(null)

    val tutorials: Flow<List<Tutorial>> = scenarioRepository.tutorialSuccessList
        .map { successList ->
            dataSource.tutorialsInfo.mapIndexed { index, tutorialData ->
                tutorialData.toDomain(index == 0 || index <= successList.size)
            }
        }

    val activeTutorial: Flow<Tutorial?> = tutorials
        .combine(activeTutorialIndex) { tutorialList, activeIndex ->
            activeIndex ?: return@combine null
            tutorialList[activeIndex]
        }

    val activeStep: Flow<TutorialStep?> = tutorialEngine.currentStep
        .map { step ->
            Log.d(TAG, "Update overlay state for step $step")
            step?.toDomain()
        }

    val activeGame: Flow<TutorialGame?> = tutorialEngine.tutorial
        .map { tutorial -> tutorial?.game?.toDomain() }

    fun isTutorialFirstTimePopupShown(): Boolean =
        sharedPrefs.isFirstTimePopupAlreadyShown()

    fun setIsTutorialFirstTimePopupShown() =
        sharedPrefs.edit().putFirstTimePopupAlreadyShown(true).apply()

    fun setupTutorialMode() {
        if (scenarioId != null) return

        scenarioId = detectionRepository.getScenarioId()

        Log.d(TAG, "Setup tutorial mode, user scenario is $scenarioId")
        scenarioRepository.startTutorialMode()
    }

    fun stopTutorialMode() {
        scenarioId ?: return

        Log.d(TAG, "Stop tutorial mode, restoring user scenario $scenarioId")

        stopTutorial()
        scenarioId?.let { detectionRepository.setScenarioId(it) }
        scenarioId = null
        allStepsCompleted = false
        activeTutorialIndex.value = null
        scenarioRepository.stopTutorialMode()
    }

    fun startTutorial(index: Int) {
        if (tutorialEngine.isStarted()) return
        if (scenarioId == null) {
            Log.e(TAG, "Tutorial mode is not setup, can't start tutorial $index")
            return
        }
        if (index < 0 || index >= dataSource.tutorialsInfo.size) {
            Log.e(TAG, "Can't start tutorial, index is invalid $index")
            return
        }

        val tutorialData = dataSource.getTutorialData(index) ?: return
        coroutineScopeMain.launch {
            val tutoScenarioDbId = withContext(Dispatchers.IO) { initTutorialScenario(index) } ?: return@launch

            Log.d(TAG, "Start tutorial $index, set current scenario to $tutoScenarioDbId")

            activeTutorialIndex.value = index
            allStepsCompleted = false
            detectionRepository.setScenarioId(Identifier(databaseId = tutoScenarioDbId))

            tutorialEngine.startTutorial(tutorialData)
        }
    }

    fun stopTutorial() {
        if (!tutorialEngine.isStarted()) return

        val scenarioIdentifier = scenarioId ?: return
        val tutorialIndex = activeTutorialIndex.value ?: return

        coroutineScopeMain.launch {
            Log.d(TAG, "Stop tutorial $tutorialIndex")

            tutorialEngine.stopTutorial()
            detectionRepository.stopDetection()

            withContext(Dispatchers.IO) {
                if (scenarioRepository.isTutorialSucceed(tutorialIndex)) {
                    Log.d(TAG, "Tutorial was already completed")
                    return@withContext
                }

                scenarioRepository.setTutorialSuccess(tutorialIndex, scenarioIdentifier, allStepsCompleted)
            }

            activeTutorialIndex.value = null
            allStepsCompleted = false
        }
    }

    fun nextTutorialStep() {
        allStepsCompleted = tutorialEngine.nextStep()
    }

    fun skipAllTutorialSteps() {
        tutorialEngine.skipAllSteps()
    }

    fun startGame(area: Rect, targetSize: Int) {
        tutorialEngine.startGame(area, targetSize)
    }

    fun onGameTargetHit(targetType: TutorialGameTargetType) {
        tutorialEngine.onGameTargetHit(targetType)
    }

    private suspend fun initTutorialScenario(tutorialIndex: Int): Long? {
        val scenarioDbId =
            if (tutorialIndex == 0) {
                scenarioRepository.addScenario(
                    Scenario(
                        id = Identifier(databaseId = DATABASE_ID_INSERTION, domainId = 0L),
                        name = "Tutorial",
                        detectionQuality = 600,
                        endConditionOperator = OR,
                    )
                )
            } else scenarioRepository.getTutorialScenarioDatabaseId(tutorialIndex - 1)?.databaseId

        if (scenarioDbId == null) Log.e(TAG, "Can't get the scenario for the tutorial $tutorialIndex")
        return scenarioDbId
    }
}

private const val TAG = "TutorialRepository"