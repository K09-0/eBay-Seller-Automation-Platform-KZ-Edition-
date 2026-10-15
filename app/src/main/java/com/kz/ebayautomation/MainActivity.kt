package com.kz.ebayautomation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var title by remember { mutableStateOf("") }
                var price by remember { mutableStateOf("") }
                var status by remember { mutableStateOf("Готов KZ -> eBay") }
                Scaffold { padding ->
                    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("eBay Seller Automation - KZ Edition", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название товара") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Цена в тенге") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { status = "Создаю: $title за $price тг" }, modifier = Modifier.fillMaxWidth()) { Text("Выставить на eBay") }
                    }
                }
            }
        }
    }
}
