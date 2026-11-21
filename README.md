# Forge Remote E

Forge Remote E（**Everyone**）は、自分のPCで動いているForgeをスマートフォンから操作する一般配布版です。Androidアプリと、iPhoneのSafariからホーム画面に追加できるWebアプリを配布しています。どちらも画像生成はPCで実行します。

| 端末 | ダウンロード | 画像付き操作ガイド |
| --- | --- | --- |
| Android | [Android 1.0 のAPKとPC用ZIP](https://github.com/asugonomi/Forge-Remote-E/releases/tag/v1.0) | [Androidの接続と操作](docs/guide-ja.md) |
| iPhone | [iOS 1.0 のPC用ZIP](https://github.com/asugonomi/Forge-Remote-E/releases/download/ios-v1.0/Forge-Remote-iOS-PC-1.0.zip) | [iPhoneの登録と操作](docs/ios-guide-ja.md) |

## Androidで使う

Android 8.0以降の端末と、HTTP APIを有効にしたForgeが必要です。Windowsでの接続手順は次のとおりです。

1. Releaseの `Forge-Remote-E-1.0.apk` をスマートフォンにインストールします。`Forge-Remote-E-PC-1.0.zip` をWindows上の任意のフォルダーに展開します。
2. 普段使っているForgeを起動します。APIが無効な環境では、Forgeの起動オプションに `--api` を追加します。
3. ZIPを展開し、**「Forge Remote E.cmd」** をダブルクリックします。フォルダー選択画面が出たら、普段使うForge本体のフォルダーを選びます。PCのブラウザーに登録用QRが開きます。新しいQRが必要になったときも、同じファイルをダブルクリックしてください。
4. スマートフォンを同じ家庭内ネットワークのWi-Fiに接続します。最初の1回はPCに表示されたQRコードをカメラで読み、アプリで「このPCを登録」を選びます。「接続済み · LAN」が表示されたら準備完了です。次回はアプリが再接続します。
5. 外出先で使う場合は、PCと端末で同じTailscaleネットワークに接続します。PCのTailscaleを有効にしてから補助ツールを起動し、Tailscaleの接続先を含むカードで登録してください。[外出先での設定と端末を追加する手順](docs/guide-ja.md)

既定では家庭内／Tailscaleの選択されたIPv4アドレスでHTTPS（TCP 17873）を受け付けます。端末は登録したPCの証明書を照合し、PCは端末ごとの登録情報を確認します。Forgeとローカル補助APIは同じPC上で利用します。Forgeのポートが7860以外なら `-ForgePort`、ForgeのPythonが `venv`、`.venv` 以外にある場合は `-Python` で指定できます。

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
.\Start-Network.ps1 -ForgeRoot 'D:\AI\Forge' -DataRoot 'D:\AI\ForgeData' -LoraRoot 'D:\Models\Lora' -TagExtension 'sd-webui-tagcomplete-neo'
```

## 画像・キュー・画面操作

- PCの出力を日付別に開き、PNG Infoから生成条件を復元できます。端末側の画像はAndroidのファイル選択から開けます。生成結果・ギャラリー・img2imgの入力は全画面表示に対応します。
- キューは端末ごとに保存します。生成中のプレビュー更新はForgeのライブプレビュー設定に従います。実行中のスキップ・中断を使う場合は `Install-JobControl.ps1 -ForgeRoot 'D:\AI\Forge'` を実行し、待機列が空のときにForgeを再起動します。これで自分の端末から送ったジョブを操作できます。
- プロンプトでは語句をタップして選び、同じ語句をドラッグして並べ替えます。改行と空行を保持します。折りたたみ・横画面では編集と結果を並べ、縦一画面でもキーボードを開いたままタグを選べます。

## USBデバッグで接続したい場合

ADB接続にも対応します。PCで `.\Start-Companion.ps1 -ForgeRoot 'D:\AI\Forge'` を起動し、Android SDK Platform Toolsで端末を認証します。別のPowerShellで `.\Connect-ForgeRemote.ps1` を実行し、アプリの「接続」→「ADB転送でPCに接続」を選びます。[Android公式のADB手順](https://developer.android.com/tools/adb)

## iPhoneで使う

PCとiPhoneを同じTailscaleネットワークに接続し、PCで **「Forge Remote iOS.cmd」** を開きます。iPhoneのカメラでQRを読み、Safariの共有メニューから **「ホーム画面に追加」** を選びます。受け付けた生成はiPhoneをロックした後もPCで続きます。[iPhone版の画像付き操作ガイド](docs/ios-guide-ja.md)

## ビルド

Androidプロジェクトは `android/` にあります。JDK 17、Android SDK 35、Build Tools 35.0.0を用意します。

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

PC補助ツールのコードと検証用のデータを生成するテストは `companion/` にあります。第三者製ソフトウェアの表記は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にまとめています。

## English quick start

The E in Forge Remote E stands for **Everyone**. On Android, download the signed APK and Windows companion ZIP. Start Forge with its API enabled, extract the ZIP, and double-click **Forge Remote E.cmd**. Join your home Wi-Fi, scan the PC's QR code and confirm the PC in the app. For remote use, connect both devices to the same Tailscale network before starting the companion. [Android release](https://github.com/asugonomi/Forge-Remote-E/releases/tag/v1.0) · [Illustrated Japanese guide](docs/guide-ja.md)

On iPhone, connect the PC and phone to the same Tailscale network. Extract the iOS companion ZIP on Windows, open **Forge Remote iOS.cmd**, and scan the QR code with the iPhone camera. In Safari, choose **Share → Add to Home Screen**. Once the PC accepts a job, it continues generating when the phone is locked. [iOS release](https://github.com/asugonomi/Forge-Remote-E/releases/tag/ios-v1.0) · [Illustrated Japanese guide](docs/ios-guide-ja.md)
