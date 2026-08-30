package com.OKK.yes.core.hooks.ui.wekit

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// used for injected 'ComposeView's
class XposedLifecycleOwner private constructor() : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    companion object {
        fun create(): XposedLifecycleOwner {
            return XposedLifecycleOwner().apply { onCreate(); onStart(); onResume() }
        }
    }
}

/** 为注入微信的 ComposeView 挂上 ViewTree owners（对齐 WeKit ComposeUtils.setLifecycleOwner）。 */
fun View.setWkLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    setViewTreeLifecycleOwner(lifecycleOwner)
    setViewTreeViewModelStoreOwner(lifecycleOwner)
    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
}
