package com.shilapi.xcertplay.transport

/** Identifies one USB device by the vendor and product IDs it advertises. */
data class UsbDeviceId(val vendorId: Int, val productId: Int)
