package com.phucdnh.messagefilter.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyOtpReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY_OTP = "com.phucdnh.messagefilter.ACTION_COPY_OTP"
        const val EXTRA_OTP_CODE = "extra_otp_code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_COPY_OTP) {
            val otpCode = intent.getStringExtra(EXTRA_OTP_CODE) ?: return
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("OTP Code", otpCode)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Đã sao chép mã OTP: $otpCode", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
