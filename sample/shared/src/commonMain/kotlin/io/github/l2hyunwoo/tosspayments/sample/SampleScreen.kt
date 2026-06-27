package io.github.l2hyunwoo.tosspayments.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.l2hyunwoo.tosspayments.ClientKey
import io.github.l2hyunwoo.tosspayments.CustomerKey
import io.github.l2hyunwoo.tosspayments.PaymentAmount
import io.github.l2hyunwoo.tosspayments.PaymentOrder
import io.github.l2hyunwoo.tosspayments.PaymentResult
import io.github.l2hyunwoo.tosspayments.PaymentWidgetConfig
import io.github.l2hyunwoo.tosspayments.TossPaymentAgreement
import io.github.l2hyunwoo.tosspayments.TossPaymentMethods
import io.github.l2hyunwoo.tosspayments.WidgetStatus
import io.github.l2hyunwoo.tosspayments.rememberTossPaymentWidget
import kotlinx.coroutines.launch

/** Themed entry point shared by every platform's app shell. */
@Composable
fun SampleApp(modifier: Modifier = Modifier) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            SampleScreen()
        }
    }
}

/**
 * Minimal demo of the consumer-facing API. Uses the public TossPayments test client key.
 *
 * The flow: render the inline method + agreement widgets, gate the pay button on readiness
 * and required-terms agreement, then call requestPayment and show the result. In a real app,
 * a Success result's paymentKey/orderId/amount must be confirmed on the merchant server.
 */
@Composable
fun SampleScreen(modifier: Modifier = Modifier) {
    val config = remember {
        PaymentWidgetConfig(
            clientKey = ClientKey("test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm"),
            customerKey = CustomerKey.ANONYMOUS,
            appScheme = "tosspaymentskmpsample",
        )
    }
    val widget = rememberTossPaymentWidget(config = config, amount = PaymentAmount(value = 50_000))

    val status by widget.status.collectAsState()
    val agreed by widget.agreedRequiredTerms.collectAsState()
    val scope = rememberCoroutineScope()
    var resultText by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("toss-payments-kmp sample", style = MaterialTheme.typography.titleMedium)
        Text("status: $status", style = MaterialTheme.typography.bodySmall)

        TossPaymentMethods(widget)
        TossPaymentAgreement(widget)

        Button(
            enabled = status == WidgetStatus.READY && agreed,
            onClick = {
                scope.launch {
                    val result = widget.requestPayment(
                        PaymentOrder(orderId = "order-" + (1..9999).random(), orderName = "Demo order"),
                    )
                    resultText = when (result) {
                        is PaymentResult.Success ->
                            "Success: paymentKey=${result.paymentKey} (confirm on server)"
                        is PaymentResult.Failure ->
                            "${result.error::class.simpleName}: ${result.error.code}"
                    }
                }
            },
        ) {
            Text("결제하기 (50,000원)")
        }

        if (resultText.isNotEmpty()) {
            Text(resultText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
