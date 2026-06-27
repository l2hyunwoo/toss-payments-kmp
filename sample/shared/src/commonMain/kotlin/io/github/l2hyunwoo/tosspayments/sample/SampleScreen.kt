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
import io.github.l2hyunwoo.tosspayments.TossPaymentWidgetSurface
import io.github.l2hyunwoo.tosspayments.WidgetStatus
import io.github.l2hyunwoo.tosspayments.rememberTossPaymentWidget
import kotlinx.coroutines.launch

@Composable
fun SampleApp(modifier: Modifier = Modifier) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            SampleScreen()
        }
    }
}

/**
 * 소비자용 API 최소 데모. 공개 테스트 client key 사용.
 * Success 결과의 paymentKey/orderId/amount는 가맹점 서버에서 confirm해야 한다.
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

        TossPaymentWidgetSurface(widget)

        Button(
            enabled = status == WidgetStatus.READY && agreed,
            onClick = {
                scope.launch {
                    val result = widget.requestPayment(
                        PaymentOrder(orderId = "order-" + (1..9999).random(), orderName = "Demo order"),
                    )
                    resultText = when (result) {
                        is PaymentResult.UnverifiedSuccess ->
                            "UnverifiedSuccess: paymentKey=${result.paymentKey} (서버에서 confirm 필요)"
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
