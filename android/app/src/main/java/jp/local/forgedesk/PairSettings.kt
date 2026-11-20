package jp.local.forgedesk

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun PairSettings(vm:ForgeModel) {
    var input by remember{mutableStateOf("")}
    var forget by remember{mutableStateOf(false)}
    val known=vm.rememberedPc
    val gateway=vm.client.gateway
    if(gateway!=null){
        val route by gateway.status.collectAsState()
        Text(gateway.pc.name+" · "+route)
    } else if(known!=null) {
        OutlinedButton(onClick=vm::useRememberedPc){Text("登録済みのPCに接続 · "+known.name)}
    } else Text("PCで発行したコードを貼り付けるか、スマートフォンのカメラでPCのQRコードを読み取ります。")
    OutlinedTextField(value=input,onValueChange={input=it.take(8192)},label={Text("PCのペアリングコード")},
        placeholder={Text("FER1.…")},modifier=Modifier.fillMaxWidth(),singleLine=true,
        visualTransformation=PasswordVisualTransformation())
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Button(enabled=input.isNotBlank() && !vm.pairingBusy,onClick={vm.readSetup(input);input=""}){Text("コードを確認")}
        if(known!=null)TextButton(enabled=!vm.pairingBusy,onClick={forget=true}){Text("登録を解除")}
    }
    if(vm.pairingError.isNotBlank() && vm.pendingCode==null) Text(vm.pairingError,color=MaterialTheme.colorScheme.error)
    if(forget) AlertDialog(onDismissRequest={forget=false},title={Text("端末からPCの登録を解除しますか？")},
        text={Text("再登録にはPCで発行する新しいコードが必要です。")},
        confirmButton={TextButton(onClick={forget=false;vm.forgetPc()}){Text("解除")}},
        dismissButton={TextButton(onClick={forget=false}){Text("キャンセル")}})
    vm.pendingCode?.let{setup->
        AlertDialog(onDismissRequest=vm::cancelSetup,title={Text("このPCとペアリングしますか？")},text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(setup.pc.name)
                Text(setup.pc.endpoints.joinToString("\n"),style=MaterialTheme.typography.bodySmall)
                Text("証明書 "+setup.pc.pin.take(16).chunked(4).joinToString(" "),style=MaterialTheme.typography.bodySmall)
                if(known!=null && known.id!=setup.pc.id)Text("登録済みのPCをこの端末で置き換えます。")
                if(vm.pairingBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(vm.pairingError.isNotBlank())Text(vm.pairingError,color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={Button(enabled=!vm.pairingBusy,onClick=vm::pair){Text(if(vm.pairingBusy)"接続中…" else "このPCを登録")}},
        dismissButton={TextButton(enabled=!vm.pairingBusy,onClick=vm::cancelSetup){Text("キャンセル")}})
    }
}
