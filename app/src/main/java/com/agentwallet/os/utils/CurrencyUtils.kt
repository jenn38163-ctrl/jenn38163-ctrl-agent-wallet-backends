package com.agentwallet.os.utils

import java.text.NumberFormat
import java.util.Locale
import java.math.BigDecimal

object CurrencyUtils {
  // Assuming 1 USDC = 1,000,000 units (6 decimals)
  private const val UNITS_PER_USDC = 1_000_000L

  fun formatUsd(amountUnits: Long): String {
    val amount = amountUnits.toDouble() / UNITS_PER_USDC
    val format = NumberFormat.getCurrencyInstance(Locale.US)
    return format.format(amount)
  }

  fun formatUsd(amountString: String): String {
    val parsed = parseCurrencyInput(amountString)
    return formatUsd(parsed)
  }

  fun parseCurrencyInput(input: String): Long {
    val cleaned = input.replace(Regex("[^\\d.]"), "")
    if (cleaned.isEmpty()) return 0L
    return try {
        val bd = BigDecimal(cleaned)
        bd.multiply(BigDecimal(UNITS_PER_USDC)).toLong()
    } catch (e: Exception) {
        0L
    }
  }
}
