package org.siloserver.silo.common.ui.marquee

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.model.auth.PasswordRequirements

@Composable
fun PasswordPolicyHints(requirements: PasswordRequirements, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (hint in requirements.hints) {
            BasicText(
                text = hint.text(),
                style = TextStyle(color = MarqueeColors.InkSecondary, fontSize = 13.sp, lineHeight = 17.sp),
            )
        }
    }
}
