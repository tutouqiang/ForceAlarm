package com.example.forcealarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.forcealarm.alarm.ChallengePrefs
import kotlin.random.Random

/**
 * 挑战弹窗：闹钟界面里的"硬解锁"通道，全屏黑底盖住闹钟。
 * 【设计精髓·防穷举】数学题答错清零重来 + 题目随机生成，
 * 半梦半醒背答案/蒙题是过不去的——这就是 Alarmy 让人真清醒的原理。
 */
@Composable
fun ChallengeDialog(
    type: Int,
    copyText: String,
    question: String,
    answer: String,
    onPass: () -> Unit,
    onAbort: () -> Unit
) {
    when (type) {
        ChallengePrefs.TYPE_COPY -> CopyChallenge(copyText, onPass, onAbort)
        ChallengePrefs.TYPE_CUSTOM -> QaChallenge(question, answer, onPass, onAbort)
        else -> MathChallenge(onPass, onAbort)
    }
}

// ————— 三种玩法共用的黑底弹窗外壳 —————

@Composable
private fun ChallengeShell(
    title: String,
    onAbort: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x1FFFFFFF))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            content()
        }
        // 放弃出口浮在底部：不通关就继续响着
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            TextButton(onClick = onAbort) {
                Text("算了，继续响着", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
        }
    }
}

// ————— 数学题：3 连对，错一题全部清零 —————

private class MathProblem(val text: String, val ans: Int)

private fun genMath(level: Int): MathProblem {
    // 前两关 2位×1位，终关 3位±3位——越到后面越逼脑细胞上班
    return if (level < 2) {
        val a = Random.nextInt(11, 100)
        val b = Random.nextInt(3, 10)
        MathProblem("$a × $b", a * b)
    } else {
        val a = Random.nextInt(100, 1000)
        val b = Random.nextInt(100, 1000)
        if (Random.nextBoolean()) MathProblem("$a + $b", a + b)
        else MathProblem("$a - $b", a - b)   // 可能得负数，减号也能输
    }
}

@Composable
private fun MathChallenge(onPass: () -> Unit, onAbort: () -> Unit) {
    val total = 3
    var level by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf(genMath(0)) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    ChallengeShell(title = "🧮 连续答对 $total 题解锁", onAbort = onAbort) {
        // 进度条：三格
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier.weight(1f).height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < level) AlarmAccent else Color(0x33FFFFFF))
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            problem.text + " = ?",
            color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter { c -> c.isDigit() || c == '-' }.take(7) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(
                textAlign = TextAlign.Center, color = Color.White
            ),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("答案", color = Color.White.copy(alpha = 0.3f)) }
        )
        Spacer(Modifier.height(10.dp))
        if (wrong) {
            Text("❌ 答错，从第 1 题重来", color = Color(0xFFFF6B6B), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = {
                val v = input.toIntOrNull() ?: return@Button
                wrong = false
                if (v == problem.ans) {
                    level++
                    input = ""
                    if (level >= total) onPass() else problem = genMath(level)
                } else {           // 清零重置——防穷举/防蒙
                    wrong = true
                    level = 0
                    input = ""
                    problem = genMath(0)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("提交", fontSize = 18.sp) }
    }
}

private val AlarmAccent = Color(0xFFFF8A3D)   // 弹窗内高亮（黑底专用，比主色亮一档）

// ————— 抄写咒语：逐字全等 —————

@Composable
private fun CopyChallenge(text: String, onPass: () -> Unit, onAbort: () -> Unit) {
    val expected = text.trim()
    var input by remember { mutableStateOf("") }
    val prefixOk = expected.startsWith(input)
    val done = input.trim() == expected

    ChallengeShell(title = "✍️ 一字不差抄写下来", onAbort = onAbort) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0x1FFFFFFF),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                expected, color = Color(0xFFFFE08A), fontSize = 20.sp,
                lineHeight = 30.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= expected.length + 4) input = it },
            isError = input.isNotEmpty() && !prefixOk,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("在下面抄写…", color = Color.White.copy(alpha = 0.3f)) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                input.isEmpty() -> "共 ${expected.length} 字"
                !prefixOk -> "❌ 第 ${input.length + 1} 个字抄错了"
                else -> "✓ 已对 ${input.length}/${expected.length} 字"
            },
            color = if (input.isNotEmpty() && !prefixOk) Color(0xFFFF6B6B) else Color(0xFF7CE7A7),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (done) onPass() },
            enabled = done,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("解锁", fontSize = 18.sp) }
    }
}

// ————— 自定义问答：睡前出题醒来答 —————

@Composable
private fun QaChallenge(
    question: String,
    answer: String,
    onPass: () -> Unit,
    onAbort: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    ChallengeShell(title = "❓ 回答你自己出的题", onAbort = onAbort) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0x1FFFFFFF),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                question.ifBlank { "（主人没录题）" },
                color = Color(0xFFFFE08A), fontSize = 22.sp, lineHeight = 32.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp).fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            isError = wrong,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("答案", color = Color.White.copy(alpha = 0.3f)) }
        )
        if (wrong) {
            Spacer(Modifier.height(6.dp))
            Text("❌ 不对，清醒一点再想", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (input.trim() == answer.trim()) onPass() else wrong = true
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("提交", fontSize = 18.sp) }
    }
}
