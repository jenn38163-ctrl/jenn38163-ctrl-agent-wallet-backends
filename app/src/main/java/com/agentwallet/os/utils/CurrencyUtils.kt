package com.agentwallet.os.utils

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
  // 1 USDC / USD = 1,000,000 micro-units (6 decimals)
  private const val UNITS_PER_USDC = 1_000_000L
  private val UNITS_BD = BigDecimal(UNITS_PER_USDC)

  fun formatAmount(amountUnits: Long, currency: String = "USD"): String {
    val normalized = currency.trim().uppercase(Locale.US).ifBlank { "USD" }
    val bd = BigDecimal(amountUnits).divide(UNITS_BD, 2, RoundingMode.HALF_UP)
    val nf = NumberFormat.getNumberInstance(Locale.US).apply {
      minimumFractionDigits = 2
      maximumFractionDigits = 2
    }
    val formatted = nf.format(bd)
    return when (normalized) {
      "USD" -> "$$formatted USD"
      "PLN" -> "$formatted PLN"
      "USDC" -> "$formatted USDC"
      else -> "$formatted $normalized"
    }
  }

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
      bd.multiply(UNITS_BD).toLong()
    } catch (e: Exception) {
      0L
    }
  }

  fun formatDecimalForApi(amountUnits: Long): String {
    if (amountUnits <= 0L) return "0.00"
    val bd = BigDecimal(amountUnits)
      .divide(UNITS_BD, 6, RoundingMode.HALF_UP)
      .stripTrailingZeros()
    val plain = bd.toPlainString()
    return if (plain.contains('.')) plain else "$plain.00"
  }
}
