package com.zz213119.virtualclicker.ui

object CoordinatePickBus {
    @Volatile
    var listener: ((Float, Float) -> Unit)? = null
}