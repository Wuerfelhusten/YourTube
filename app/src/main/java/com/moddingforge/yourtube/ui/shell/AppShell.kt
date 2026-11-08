package com.moddingforge.yourtube.ui.shell

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.moddingforge.yourtube.BuildConfig
import com.moddingforge.yourtube.R
import com.moddingforge.yourtube.data.SearchHistory
import com.moddingforge.yourtube.data.SearchSuggestions
import androidx.compose.foundation.layout.asPaddingValues
import com.moddingforge.yourtube.AppContainer
import com.moddingforge.yourtube.ui.settings.SettingsScreen
import com.moddingforge.yourtube.ui.player.MiniPlayerHeight
import com.moddingforge.yourtube.ui.player.PlayerLayer
import com.moddingforge.yourtube.web.Page
import com.moddingforge.yourtube.web.PlayerController
import com.moddingforge.yourtube.web.PlayerMode
import com.moddingforge.yourtube.web.Tab
import com.moddingforge.yourtube.web.WebState
import com.moddingforge.yourtube.web.WebViewHolder
import kotlinx.coroutines.delay

/**
 * YourTube's native chrome around the WebView, modelled on the official app:
 * top bar (logo, search, menu) except on watch/Shorts, compact bottom tabs (rail on tablets),
 * pull-to-refresh on feeds, black status bar area while a video is open, native search screen.
 */
@Composable
fun AppShell(
    container: AppContainer,
    activity: ComponentActivity,
    suggestions: SearchSuggestions,
    history: SearchHistory,
) {
    val controller = container.playerController
    val holder = controller.feed
    val settingsOpen by controller.settingsOpen.collectAsStateWithLifecycle()
    val appSettings by container.settings.settings.collectAsStateWithLifecycle()
    val state by holder.state.collectAsStateWithLifecycle()
    val playerMode by controller.mode.collectAsStateWithLifecycle()
    val page = if (state.url.isEmpty()) Page.HOME else Page.of(state.url)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.shellRequests.collect { r ->
            if (r == com.moddingforge.yourtube.ShellRequest.OpenSearch) { searchOpen = true; container.shellRequests.value = null }
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val dark = isSystemInDarkTheme()
    val tabBarShown = !wide && page.showsTabBar
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    SystemBarIcons(lightIcons = dark || page == Page.WATCH || page == Page.SHORTS || playerMode == PlayerMode.EXPANDED)

    BackHandler(enabled = !searchOpen) {
        if (!holder.goBack()) activity.moveTaskToBack(true)
    }

    val chromeBg = if (page == Page.WATCH || page == Page.SHORTS) Color.Black else MaterialTheme.colorScheme.surface

    // No Surface in this tree, so set the content color explicitly (else text/icons stay black in dark mode).
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxSize().background(chromeBg)) {
        if (wide && page.showsTabBar) TabRail(page, appSettings.showShortsTab, onSelect = holder::selectTab)

        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (page.showsTopBar) {
                TopBar(
                    page = page,
                    query = Page.searchQuery(state.url),
                    onLogo = { holder.selectTab(Tab.HOME) },
                    onSearch = { searchOpen = true },
                    onBack = { holder.goBack() },
                    signedIn = state.signedIn,
                    onOpen = holder::load,
                    onSignIn = holder::signIn,
                    brand = com.moddingforge.yourtube.ui.branding.rememberBrand(appSettings),
                )
            } else {
                Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            }

            // Keep the feed's last items reachable above the mini player.
            val miniPad = if (playerMode == PlayerMode.MINI) MiniPlayerHeight else 0.dp
            Box(Modifier.weight(1f).fillMaxWidth().imePadding().padding(bottom = miniPad)) {
                WebContent(holder, activity, state, page)
                if (state.progress in 1..99) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (BuildConfig.DEBUG) DebugChip(state, holder, Modifier.align(Alignment.BottomStart))
                UpdateBanner(container.updater, Modifier.align(Alignment.BottomCenter))
            }

            if (tabBarShown) {
                TabBar(page, appSettings.showShortsTab, onSelect = holder::selectTab)
            } else {
                Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    // Player above the feed: full screen, or the mini bar right above the tab bar.
    PlayerLayer(
        controller = controller,
        extras = container.playerExtras,
        activity = activity,
        miniBottomPadding = navInset + if (tabBarShown) TabBarHeight + 1.dp else 0.dp,
    )

    if (searchOpen) {
        SearchScreen(
            initialQuery = Page.searchQuery(state.url).orEmpty(),
            suggestions = suggestions,
            history = history,
            onSearch = { q -> searchOpen = false; holder.search(q) },
            onClose = { searchOpen = false },
        )
    }

    if (settingsOpen) SettingsScreen(container, onClose = controller::closeSettings)

    WhatsNewPopup(container.updater)

    LinkHint()
    }
}

@Composable
private fun SystemBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
    }
}

// --- WebView with pull-to-refresh ---------------------------------------------------------

@Composable
private fun WebContent(holder: WebViewHolder, activity: ComponentActivity, state: WebState, page: Page) {
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val spinnerBg = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    // A new generation means the renderer crashed and the WebView was recreated.
    key(state.generation) {
        AndroidView(
            factory = { ctx ->
                SwipeRefreshLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setColorSchemeColors(accent)
                    setProgressBackgroundColorSchemeColor(spinnerBg)
                    addView(holder.attach(activity))
                    setOnRefreshListener { holder.reload() }
                    if (state.generation > 0) holder.load(holder.consumePendingUrl()) else holder.loadIfEmpty()
                }
            },
            update = { swipe ->
                swipe.isEnabled = page.allowsPullToRefresh
                if (state.progress >= 100) swipe.isRefreshing = false
            },
            onRelease = { swipe ->
                holder.detach(activity)
                swipe.removeAllViews()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// --- top bar ------------------------------------------------------------------------------

@Composable
private fun TopBar(
    page: Page,
    query: String?,
    onLogo: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    signedIn: Boolean,
    onOpen: (String) -> Unit,
    onSignIn: () -> Unit,
    brand: com.moddingforge.yourtube.ui.branding.BrandSpec,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(56.dp)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page == Page.RESULTS) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            // Official app: the query stays visible in a search pill on the results page.
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(query.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            if (brand.visible) {
                com.moddingforge.yourtube.ui.branding.BrandMark(
                    brand,
                    Modifier.weight(1f, fill = false).clickable(onClick = onLogo).padding(start = 16.dp, end = 8.dp).height(56.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // The entries of m.youtube's own (now hidden) top bar menu. YourTube Settings is
                // reached via YouTube settings (entry at the top of that page).
                if (!signedIn) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_sign_in)) }, onClick = { menu = false; onSignIn() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_yt_settings)) },
                    onClick = { menu = false; onOpen("https://m.youtube.com/select_site") })
                if (signedIn) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_your_data)) },
                        onClick = { menu = false; onOpen("https://myaccount.google.com/yourdata/youtube") })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_help)) },
                    onClick = { menu = false; onOpen("https://support.google.com/youtube/?hl=" + java.util.Locale.getDefault().language) })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_terms)) },
                    onClick = { menu = false; onOpen("https://www.youtube.com/t/terms") })
            }
        }
    }
}

// --- tabs -----------------------------------------------------------------------------------

private data class TabSpec(val tab: Tab, val label: Int, val icon: @Composable (Boolean) -> Painter)

@Composable
private fun tabSpecs(): List<TabSpec> = listOf(
    TabSpec(Tab.HOME, R.string.tab_home) { sel -> rememberVectorPainter(if (sel) Icons.Filled.Home else Icons.Outlined.Home) },
    TabSpec(Tab.SHORTS, R.string.tab_shorts) { sel -> painterResource(if (sel) R.drawable.ic_shorts_filled else R.drawable.ic_shorts) },
    TabSpec(Tab.SUBSCRIPTIONS, R.string.tab_subscriptions) { sel -> rememberVectorPainter(if (sel) Icons.Filled.Subscriptions else Icons.Outlined.Subscriptions) },
    TabSpec(Tab.YOU, R.string.tab_you) { sel -> rememberVectorPainter(if (sel) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle) },
)

private val TabBarHeight = 56.dp

/** Compact bottom bar like the official app (56dp, small labels), not the 80dp Material bar. */
@Composable
private fun TabBar(page: Page, showShorts: Boolean, onSelect: (Tab) -> Unit) {
    val selected = Tab.of(page)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(TabBarHeight)) {
            for (spec in tabSpecs().filter { showShorts || it.tab != Tab.SHORTS }) {
                val sel = spec.tab == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(spec.tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(spec.icon(sel), contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(stringResource(spec.label), fontSize = 10.sp, maxLines = 1,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun TabRail(page: Page, showShorts: Boolean, onSelect: (Tab) -> Unit) {
    val selected = Tab.of(page)
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.statusBarsPadding()) {
        for (spec in tabSpecs().filter { showShorts || it.tab != Tab.SHORTS }) {
            NavigationRailItem(
                selected = spec.tab == selected,
                onClick = { onSelect(spec.tab) },
                icon = { Icon(spec.icon(spec.tab == selected), contentDescription = null) },
                label = { Text(stringResource(spec.label)) },
            )
        }
    }
}

// --- debug ----------------------------------------------------------------------------------

/** Debug-only overlay: bridge status, sign-in, adblock counters and the current URL. */
@Composable
private fun DebugChip(state: WebState, holder: WebViewHolder, modifier: Modifier = Modifier) {
    var blocked by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            blocked = holder.adblock.blockedRequests.get()
            skipped = holder.adblock.skippedAds.get()
            delay(1_000)
        }
    }
    val bridge = when {
        !state.bridgeReady -> "br -"
        state.bridgePingMs >= 0 -> "br ${state.bridgePingMs}ms"
        else -> "br ok"
    }
    Text(
        text = "$bridge | ${if (state.signedIn) "acc" else "anon"} | ab $blocked/$skipped",
        color = Color.White,
        fontSize = 9.sp,
        maxLines = 1,
        modifier = modifier
            .padding(4.dp)
            .widthIn(max = 220.dp)
            .background(Color(0x88000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
