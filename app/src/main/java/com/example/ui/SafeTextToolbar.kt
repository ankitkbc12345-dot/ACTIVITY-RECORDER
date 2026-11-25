package com.example.ui

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Provides a lifecycle-safe [TextToolbar] that clears its [ActionMode] reference in
 * [ActionMode.Callback2.onDestroyActionMode] and before invoking [ActionMode.finish], preventing
 * `DecorView[] Destroying unexpected ActionMode instance of TYPE_FLOATING` logcat errors in
 * Compose when focus changes, menu actions complete, or screens navigate away.
 */
@Composable
fun ProvideSafeTextToolbar(content: @Composable () -> Unit) {
  val view = LocalView.current
  val toolbar = remember(view) { SafeAndroidTextToolbar(view) }
  DisposableEffect(toolbar) {
    onDispose {
      toolbar.hide()
    }
  }
  CompositionLocalProvider(LocalTextToolbar provides toolbar) {
    content()
  }
}

internal class SafeAndroidTextToolbar(
  private val view: View,
  private val startFloatingActionMode: (View, ActionMode.Callback2) -> ActionMode? = { target, cb ->
    target.startActionMode(cb, ActionMode.TYPE_FLOATING)
  },
) : TextToolbar {
  private var actionMode: ActionMode? = null
  private var rect: Rect = Rect.Zero
  private var onCopyRequested: (() -> Unit)? = null
  private var onPasteRequested: (() -> Unit)? = null
  private var onCutRequested: (() -> Unit)? = null
  private var onSelectAllRequested: (() -> Unit)? = null

  override var status: TextToolbarStatus = TextToolbarStatus.Hidden
    private set

  internal val actionModeCallback: ActionMode.Callback2 =
    object : ActionMode.Callback2() {
      override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        if (mode == null || menu == null) return false
        updateMenuItems(menu)
        return true
      }

      override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        if (mode == null || menu == null) return false
        updateMenuItems(menu)
        return true
      }

      override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item?.itemId) {
          MENU_ID_COPY -> onCopyRequested?.invoke()
          MENU_ID_PASTE -> onPasteRequested?.invoke()
          MENU_ID_CUT -> onCutRequested?.invoke()
          MENU_ID_SELECT_ALL -> onSelectAllRequested?.invoke()
          else -> return false
        }
        // Only finish if the action callback itself did not already call hide().
        if (actionMode === mode) {
          actionMode = null
          status = TextToolbarStatus.Hidden
          mode?.finish()
        }
        return true
      }

      override fun onDestroyActionMode(mode: ActionMode?) {
        if (actionMode === mode) {
          actionMode = null
          status = TextToolbarStatus.Hidden
        }
      }

      override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: AndroidRect?) {
        outRect?.set(
          rect.left.toInt(),
          rect.top.toInt(),
          rect.right.toInt(),
          rect.bottom.toInt(),
        )
      }
    }

  override fun showMenu(
    rect: Rect,
    onCopyRequested: (() -> Unit)?,
    onPasteRequested: (() -> Unit)?,
    onCutRequested: (() -> Unit)?,
    onSelectAllRequested: (() -> Unit)?,
  ) {
    this.rect = rect
    this.onCopyRequested = onCopyRequested
    this.onPasteRequested = onPasteRequested
    this.onCutRequested = onCutRequested
    this.onSelectAllRequested = onSelectAllRequested

    if (!view.isAttachedToWindow) {
      hide()
      return
    }

    val current = actionMode
    if (current == null) {
      status = TextToolbarStatus.Shown
      val started = startFloatingActionMode(view, actionModeCallback)
      actionMode = started
      if (started == null) {
        status = TextToolbarStatus.Hidden
      }
    } else {
      status = TextToolbarStatus.Shown
      current.invalidate()
    }
  }

  override fun hide() {
    status = TextToolbarStatus.Hidden
    val current = actionMode
    actionMode = null
    current?.finish()
  }

  private fun updateMenuItems(menu: Menu) {
    addOrRemoveMenuItem(menu, MENU_ID_COPY, android.R.string.copy, onCopyRequested != null)
    addOrRemoveMenuItem(menu, MENU_ID_PASTE, android.R.string.paste, onPasteRequested != null)
    addOrRemoveMenuItem(menu, MENU_ID_CUT, android.R.string.cut, onCutRequested != null)
    addOrRemoveMenuItem(menu, MENU_ID_SELECT_ALL, android.R.string.selectAll, onSelectAllRequested != null)
  }

  private fun addOrRemoveMenuItem(menu: Menu, itemId: Int, titleRes: Int, shouldShow: Boolean) {
    val existing = menu.findItem(itemId)
    if (shouldShow && existing == null) {
      menu.add(Menu.NONE, itemId, itemId, titleRes).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    } else if (!shouldShow && existing != null) {
      menu.removeItem(itemId)
    }
  }

  private companion object {
    const val MENU_ID_COPY = 0
    const val MENU_ID_PASTE = 1
    const val MENU_ID_CUT = 2
    const val MENU_ID_SELECT_ALL = 3
  }
}
