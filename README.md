# Forge Remote E

Forge Remote E（**Everyone**）は、自分のPCで動いているForgeをAndroidのタッチ画面から操作する一般配布版です。画像生成はPCで実行します。スマートフォンにはプロンプト、生成キュー、画像ギャラリー、LoRA一覧を備えています。

**[ReleaseからAPKとPC補助ツールをダウンロード](https://github.com/asugonomi/Forge-Remote-E/releases/latest)**

どなたでも配布APKと付属のPC補助ツールをダウンロードし、ご自身の環境にインストールして使用できます。

## はじめに

Android 8.0以降の端末と、HTTP APIを有効にしたForgeが必要です。Windowsでの接続手順は次のとおりです。

1. Releaseの `Forge-Remote-E-1.0.apk` をスマートフォンにインストールします。`Forge-Remote-E-PC-1.0.zip` をWindows上の任意のフォルダーに展開します。
2. 普段使っているForgeを起動します。APIが無効な環境では、Forgeの起動オプションに `--api` を追加します。
3. ZIPを展開したフォルダーでPowerShellを開き、下のコマンドを実行します。`D:\AI\Forge` を自分のForgeフォルダーに置き換えます。このPowerShellはアプリを使う間、開いておきます。

   ```powershell
   .\Start-Companion.ps1 -ForgeRoot 'D:\AI\Forge'
   ```

4. Android SDK Platform ToolsをPCに用意し、USBデバッグまたはワイヤレスデバッグで端末とPCをペアリングします。`adb devices` で端末が `device` と表示されることを確かめます。[Android公式の接続手順](https://developer.android.com/tools/adb)
5. 展開先でもう一つPowerShellを開き、次のコマンドを実行します。アプリの「接続」でForgeと補助APIの状態を確認できます。再接続が必要なときもこのコマンドを使います。

   ```powershell
   .\Connect-ForgeRemote.ps1
   ```

Forgeのポートが7860以外なら、両方のコマンドに同じ `-ForgePort` を指定してください。仮想環境がForgeの `venv`、`.venv` 以外にある場合は、起動時に `-Python` でForgeが使っているPython実行ファイルを指定できます。既定では補助ツールはPCの `127.0.0.1:17872` で待ち受け、アプリは認証済みADBの転送を使います。

## 自分の拡張・辞書を使う

| 機能 | PC側で使うもの | アプリの操作 |
| --- | --- | --- |
| 生成と初期値 | 接続中のForgeの生成設定、選択中のプリセット、サンプラーとスケジューラー | txt2img・img2imgそれぞれで編集、端末の初期値を登録、下書きを再開 |
| タグ補完 | 有効なTag Autocomplete互換拡張が選択しているメイン辞書・追加CSV・翻訳CSV | タグの使用件数順に検索。候補を選ぶとPCの挿入規則で整形し、末尾に半角カンマとスペースを追加 |
| LoRA | PCのLoRA一覧とプレビュー、利用できる場合はTag Autocompleteのキャッシュ | フォルダーや名前で絞り込み、サムネイルを選んで挿入 |
| ADetailer | PCに導入したADetailerと検出モデル | モデル、信頼度、マスクなどを設定して生成 |
| AR-PlusPlus | 有効な互換拡張の比率・解像度ファイル | プリセット選択、縦横交換、手動の幅・高さ入力 |
| その他の生成拡張 | Forgeの `/sdapi/v1/script-info` に公開された生成スクリプトと引数 | 「拡張」からスイッチ、数値、選択肢、文字列を指定 |

Tag Autocomplete本家とNeo系の互換CSVを対象にしています。候補の件数はメイン辞書に記録された使用回数です。ブラウザー内のJavaScriptや独自画面だけで動く拡張、独自オブジェクトやファイルを引数とする拡張には個別対応が必要です。アプリに各利用者のタグ辞書、モデル、生成画像、PCの設定ファイルは同梱していません。

データを別ディレクトリに置いている場合は `-DataRoot`、LoRAの場所を指定する場合は `-LoraRoot` が使えます。ForgeがAPIに公開していない辞書の設定は、データフォルダーの `config.json` から取得します。別の設定ファイルを使う環境では `-Config` に指定してください。複数の互換拡張が見つかった場合は、普段使っている拡張のフォルダー名を `-TagExtension` または `-ArExtension` に指定します。

```powershell
.\Start-Companion.ps1 -ForgeRoot 'D:\AI\Forge' -DataRoot 'D:\AI\ForgeData' -LoraRoot 'D:\Models\Lora' -TagExtension 'sd-webui-tagcomplete-neo'
```

## 画像・キュー・画面操作

- PCの出力を日付別に開き、PNG Infoから生成条件を復元できます。端末側の画像はAndroidのファイル選択から開けます。生成結果・ギャラリー・img2imgの入力は全画面表示に対応します。
- キューは端末ごとに保存します。生成中のプレビュー更新はForgeのライブプレビュー設定に従います。実行中のスキップ・中断を使う場合は `Install-JobControl.ps1 -ForgeRoot 'D:\AI\Forge'` を実行し、待機列が空のときにForgeを再起動します。これで自分の端末から送ったジョブを操作できます。
- プロンプトでは語句をタップして選び、同じ語句をドラッグして並べ替えます。改行と空行を保持します。折りたたみ・横画面では編集と結果を並べ、縦一画面でもキーボードを開いたままタグを選べます。
- 自宅外では、VPN内で認証と有効なHTTPS証明書を備えたゲートウェイを用意し、ForgeのAPIと補助APIの両方を保護してください。標準手順はUSBまたは同一ネットワークのワイヤレスデバッグです。

## ビルド

Androidプロジェクトは `android/` にあります。JDK 17、Android SDK 35、Build Tools 35.0.0を用意します。

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

PC補助ツールのコードと検証用のデータを生成するテストは `companion/` にあります。第三者製ソフトウェアの表記は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にまとめています。

## English quick start

The E in Forge Remote E stands for **Everyone**. This native Android client connects to your own Forge installation. Download the signed APK and the Windows PC companion ZIP from the [latest release](https://github.com/asugonomi/Forge-Remote-E/releases/latest). Start Forge with its API enabled. Run `Start-Companion.ps1 -ForgeRoot 'D:\AI\Forge'` on the PC, authorize the Android device over USB or wireless ADB, then run `Connect-ForgeRemote.ps1` in a second terminal. The companion reads the enabled compatible extensions and the dictionaries configured on that PC.
