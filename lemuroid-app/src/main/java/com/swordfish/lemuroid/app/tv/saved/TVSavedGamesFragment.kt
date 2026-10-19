package com.swordfish.lemuroid.app.tv.saved

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGameGroupUiModel
import com.swordfish.lemuroid.app.mobile.feature.installed.InstalledGamesViewModel
import com.swordfish.lemuroid.app.mobile.feature.installed.StorageAvailabilityMonitor
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.search.SystemSearchResolver
import com.swordfish.lemuroid.app.tv.home.SavedGamePresenter
import com.swordfish.lemuroid.app.tv.home.TVHomeFragment
import com.swordfish.lemuroid.app.tv.shared.TVAmbientBackground
import com.swordfish.lemuroid.app.tv.shared.TVSavedGameActions
import com.swordfish.lemuroid.app.tv.shared.TVVerticalGridPresenter
import com.swordfish.lemuroid.app.tv.shared.TV_GAME_GRID_COLUMNS
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TVSavedGamesFragment : VerticalGridSupportFragment() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var directoriesManager: DirectoriesManager

    private var savedGameActions: TVSavedGameActions? = null
    private var hasHadItems = false

    init {
        val gridPresenter = TVVerticalGridPresenter()
        gridPresenter.numberOfColumns = TV_GAME_GRID_COLUMNS
        setGridPresenter(gridPresenter)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        title = getString(R.string.title_installed)

        val appContext = requireContext().applicationContext
        val storageAvailabilityMonitor = StorageAvailabilityMonitor(appContext)
        val systemSearchResolver = SystemSearchResolver(appContext)
        val factory =
            InstalledGamesViewModel.Factory(
                retrogradeDb = retrogradeDb,
                directoriesManager = directoriesManager,
                storageAvailabilityMonitor = storageAvailabilityMonitor,
                systemSearchResolver = systemSearchResolver,
            )
        val viewModel = ViewModelProvider(this, factory)[InstalledGamesViewModel::class.java]

        val arrayAdapter = ArrayObjectAdapter(SavedGamePresenter(gameInteractor, showInfo = true))
        this.adapter = arrayAdapter

        val actions =
            TVSavedGameActions(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope,
                gameInteractor = gameInteractor,
                savedVariants = { game -> viewModel.getInstalledVariants(game.systemId, game.title) },
                isAvailable = { game ->
                    withContext(Dispatchers.IO) {
                        storageAvailabilityMonitor.isGameAvailable(game)
                    }
                },
            )
        savedGameActions = actions

        val ambientBackground = TVAmbientBackground.attach(this)
        setOnItemViewSelectedListener { _, item, _, _ ->
            ambientBackground.onItemSelected((item as? InstalledGameGroupUiModel)?.group?.game ?: item)
        }

        onItemViewClickedListener =
            OnItemViewClickedListener { _, item, _, _ ->
                if (item is InstalledGameGroupUiModel) {
                    actions.onSavedGameClick(item)
                }
            }

        launchOnState(Lifecycle.State.RESUMED) {
            viewModel.installedGames.collect { items ->
                if (items.isNotEmpty()) {
                    hasHadItems = true
                } else if (hasHadItems) {
                    findNavController().popBackStack()
                    return@collect
                }
                arrayAdapter.setItems(items, TVHomeFragment.LEANBACK_SAVED_GAME_DIFF_CALLBACK)
            }
        }
    }

    override fun onDestroyView() {
        savedGameActions = null
        super.onDestroyView()
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    @dagger.Module
    class Module
}
