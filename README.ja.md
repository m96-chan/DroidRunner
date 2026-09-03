# DroidRunner

**眠っているAndroid端末を、GitHub Actionsの実機Runnerへ。**

[English README is here](README.md)

**[紹介ページとチュートリアル](https://droidrunner.m96-chan.dev/ja/)** — 概要、画面、端末セットアップの手順。

DroidRunnerは、ARM64 Android端末をRepository単位のGitHub Actions
self-hosted runnerとして動作させるAndroidアプリです。

Termuxやroot権限、PCへのUSB常時接続は必要ありません。APKがLinux実行環境と
公式GitHub Actions Runnerの導入・登録・常駐を管理します。通常のARM64ビルドに加え、
Android端末固有のNNAPIやベンダーNPUをCIから検証できる端末プールを目指しています。

> [!WARNING]
> 現在は初期PoCです。Runner管理の基礎実装はありますが、配布可能なruntime bundleと
> NPU Device Agentは組み込みベンチマークのみ対応で、任意モデルの実行は開発中です。
> 本番環境や信頼できないRepositoryでは使用しないでください。

## 目標

- 余っているスマートフォンやタブレットをARM64ビルド資源として再利用する
- Qualcomm、Google Tensor、MediaTek、Exynosなどの実機差をGitHub Actionsから検証する
- 複数端末をラベルで分類し、必要なSoCやNPUへジョブをルーティングする
- Androidアプリだけで導入・更新・監視を完結させる
- バッテリー、充電状態、発熱を考慮して安全にジョブを受け付ける

## 特徴

- **APK単体** — Termuxなどのコンパニオンアプリは不要
- **root不要** — PRoot上でLinux ARM64環境を実行
- **公式Runner** — GitHub公式のLinux ARM64 Actions Runnerを使用
- **RepositoryまたはOrganizationスコープ** — 1つのリポジトリ、または組織内の全リポジトリを1台で担当
- **GitHub Appログイン** — Device Flowでサインインしてリポジトリを選ぶだけ。PATの手動発行は不要
- **端末自動分類** — Android API、SoC、NPU候補からRunnerラベルを生成
- **安全な資格情報管理** — PATをAndroid Keystoreで暗号化
- **バックグラウンド待機** — Foreground ServiceとWakeLockでRunnerを維持
- **改ざん検出** — runtime bundleを展開する前にSHA-256を検証
- **btop風ダッシュボード** — CPU・メモリ・バッテリー・温度・ディスク・ネットワークの
  リソースモニターとRunner稼働状況をリアルタイム表示
- **自己防衛** — 非充電・残量低下・高温・容量不足のあいだはジョブを保留し、
  異常終了しても自動でリスナーを再起動。保留中は実際にGitHubからofflineに見える
  (「保留したつもり」で終わらない)
- **状態を隠さない** — 通知にRunnerの状態と、保留中はその理由を表示。
  Picture-in-Pictureで他アプリを使いながら見ておける
- **ephemeralモード** — ジョブごとに再登録し、work directoryを消去(任意)

## 全体構成

```mermaid
flowchart TD
    GH["GitHub Actions"] --> LR["公式Linux ARM64 Runner"]
    subgraph APK["DroidRunner APK"]
        CT["Android Controller"] --> PR["PRoot + Linux rootfs"]
        PR --> LR
        LR --> DA["Device Agent"]
        DA --> HW["NNAPI / QNN / Neuron / ENN"]
    end
    CT --> KS["Android Keystore"]
```

通常のシェル、Git、Node.js、Python、Rust、Go、GradleなどはPRoot側で実行します。
Android APIやNPUへアクセスするテストは、loopback APIを介してAPK内のDevice Agentへ依頼します。

## ダッシュボード

アプリのメイン画面は、btopに着想を得たターミナル風ダッシュボードです
(Jetpack Composeで描画)。

<img src="docs/images/dashboard.png" alt="DroidRunnerダッシュボード" width="320">


- **cpu** — 負荷履歴グラフと、コアごとのメーター+現在周波数
  (コア負荷は読み取り可能なら`/proc/stat`の差分、不可なら最大周波数に対する
  現在のscaling周波数を使用)
- **mem / disk** — RAMとアプリ専用ストレージの使用率メーターと履歴
- **pwr/net** — バッテリー残量と充電状態、バッテリー温度、Androidサーマルステータス、
  ネットワークスループット
- **runner** — Runnerの状態(停止 / 起動中 / ジョブ待機 / ジョブ実行中)、登録先
  Repository、稼働時間、成功・失敗ジョブ数、ロックされたまま再起動した端末が
  どれだけオフラインだったか、Runnerログのライブテール
- **setup** — 別画面(⚙)にGitHubサインイン・リポジトリ選択・runtime導入・登録に加え、
  ジョブ受付ポリシー(充電・バッテリー・温度・空き容量の閾値、ephemeralモード、
  起動時自動スタート)を集約。登録済みならアプリ起動時にRunnerが自動スタート

Runnerの状態は、Foreground Serviceが公式Runnerのlistener出力をパースし、
`StateFlow`としてUIへストリームします。

## 現在の実装状況

| 機能 | 状態 |
| --- | --- |
| btop風ダッシュボードUI | PoC実装済み |
| GitHub App Device Flowログイン+リポジトリ選択 | PoC実装済み |
| Repository登録トークンの取得 | PoC実装済み |
| Organizationスコープのrunner | PoC実装済み |
| 資格情報のKeystore保存(userトークン / PAT) | PoC実装済み |
| refresh tokenによるサインインの自動更新 | PoC実装済み |
| runtime bundleの取得・SHA-256検証 | PoC実装済み |
| prootのNDKビルド(APK同梱)+ bundle CI | PoC実装済み |
| PRootでの公式Runner起動 | 実機検証済み(ジョブ実行成功) |
| Foreground Service(Runner状態パース付き) | PoC実装済み |
| SoC/NPU候補ラベル | PoC実装済み |
| 充電・温度・ストレージ制御 | 実機検証済み(保留中はGitHubからofflineに見える) |
| ephemeral runner(ジョブ後クリーンアップ) | PoC実装済み |
| リスナー異常終了からの復旧 | PoC実装済み |
| NPU Device Agent(loopback API・NNAPI probe・CLI) | PoC実装済み |
| probe検証済みNNAPIラベル | PoC実装済み |
| 任意モデル(`.tflite`)の実行 | 設計済み・未実装 |
| 複数端末ダッシュボード | 未実装 |
| 署名付きruntime manifest | PoC実装済み |
| Runner状態・保留理由・警告を出す通知 | PoC実装済み |
| Picture-in-PictureでのRunner表示 | PoC実装済み |

## Runnerラベル

公式Runnerが追加する標準ラベルに加え、DroidRunnerは次のカスタムラベルを使用します。

| ラベル | 意味 |
| --- | --- |
| `android` | DroidRunner上で動作するAndroid実機 |
| `arm64` | ARM64端末 |
| `android-api-N` | Android API Level |
| `soc-*` | 検出したSoC情報 |
| `android-npu` | NPU搭載候補端末 |
| `android-no-npu` | NPUを検出できなかった端末 |
| `npu-qnn` | Qualcomm QNN候補 |
| `npu-tflite` | Google Tensor / LiteRT候補 |
| `npu-neuron` | MediaTek Neuron候補 |
| `npu-enn` | Samsung ENN候補 |

SoC名による判定はあくまで候補です。完成版ではDevice Agentが実際にバックエンドをprobeし、
利用可能性を確認できたラベルだけを公開します。

## Workflow例

### 任意のAndroid端末で実行

```yaml
name: Android device test

on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  test:
    runs-on: [self-hosted, android, arm64]
    steps:
      - uses: actions/checkout@v4
      - run: uname -a
      - run: ./ci/test-arm64.sh
```

### 端末のNPUを叩く

```yaml
jobs:
  npu-test:
    runs-on: [self-hosted, android, nnapi-accelerator]
    steps:
      - run: droidrunner-device capabilities        # 端末情報・温度・NNAPIドライバ一覧
      - run: droidrunner-device bench-all           # 全ドライバでCONV_2Dベンチ
      - run: droidrunner-device test conv --device mtk-neuron_shim --iterations 50
```

`droidrunner-device`はruntime bundleに同梱され、Device Agentとの通信を代行します。
MediaTek MT6899端末での実測例:

```text
DEVICE                       AVG_US     GFLOPS
mtk-dsp_shim                      - compilation_finish
mtk-mdla_shim                     - compilation_finish
mtk-neuron_shim              4148.9       4.55
nnapi-reference              1107.7      17.04
```

現在のAgentは組み込みのADD/CONV_2Dベンチマークのみ実行できます。任意モデルの実行は
次のマイルストーンです(issue #4)。float32の畳み込みを拒否するドライバ(ここではDSPと
MDLA)は量子化モデル向けで、SoC名ではなくprobeでラベルを決めるべき理由がこれです。

## 必要環境

### Android端末

- ARM64(`arm64-v8a`)
- Android 9 / API 28以上
- 数GB程度の空き容量
- 安定したネットワーク接続
- 長時間運用では充電器と冷却手段を推奨

32bit ARM、x86 Android、Docker、KVM、nested virtualizationには対応しません。

### インストール

リリースは[GitHub Releases](https://github.com/m96-chan/DroidRunner/releases)に
`droidrunner-v<version>.apk`(arm64のみ)として公開しています。

**[Obtainium](https://github.com/ImranR98/Obtainium)を使う** — 無人運用のRunnerが
リリースに気づいてもらうのを待つ必要がなくなるため、こちらを推奨します:

1. アプリを追加 → `https://github.com/m96-chan/DroidRunner` を貼り付け
2. APKフィルタ(1リリースに複数アセットが載る場合のみ必要):
   `droidrunner-v.*\.apk`

以後、新しいタグが公開されるとその場で更新されます。

**手動**: 最新リリースからAPKをダウンロードしてインストールします。

> [!NOTE]
> 上書き更新できるのは署名鍵が同じ場合だけです。別の署名(将来のF-Droid版や自前ビルド)へ
> 移る場合は一度アンインストールが必要で、**Runnerの登録と保存済みGitHub認証情報は失われます**
> (端末の再登録が必要になります)。

Google Playは配布先として想定していません。ストア外から取得したコードを実行することが
アプリの目的そのもので、Device & Network Abuseポリシーの動的コード実行に該当するためです。

## ビルド環境

- JDK 17
- Android SDK 35
- Android Studio Ladybug以降、またはGradle 8.10系

## ビルド

```bash
git clone <repository-url>
cd DroidRunner
ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/<version> runtime/build-proot.sh
gradle assembleDebug
```

`build-proot.sh`はNDKでprootをクロスビルドして`app/src/main/jniLibs/`へ配置します
(初回に1度必要。Android 10以降はAPK同梱バイナリしかexecできないため)。

生成されるAPK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

このRepositoryにはGitHub Actions用のビルド定義も含まれています。

### リリースの公開

`v*`タグをpushするとAPKがビルドされ公開されます。`versionName`と`versionCode`は
タグから導出されるため、更新版は必ず既存版より上位になります(タグなしのローカルビルドは
`0.0.0-dev`)。

リリースは固定の鍵で署名する必要があり、debug鍵でタグ付きリリースをビルドしようとすると
ビルドが失敗します。鍵は1つ作って安全に保管し、リポジトリのsecretsへ登録してください:

```bash
keytool -genkey -v -keystore release.jks -alias droidrunner \
    -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # → ANDROID_KEYSTORE_BASE64
```

secrets: `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD`。鍵を失うと、新しい鍵へ移行するために利用者全員が
アンインストールと端末の再登録を強いられます。

## セットアップ

1. DroidRunner APKを端末へインストールする
2. setup画面の**Connect GitHub**を押す — 8桁のコードが表示される(クリップボードへ
   自動コピー)。`github.com/login/device`でコードを入力して承認する
3. DroidRunner GitHub Appがどのリポジトリにも未インストールの場合、アプリが
   インストールを促すので、Runnerを割り当てたいリポジトリへインストールする
4. **スコープ**を選ぶ(単一リポジトリ、またはアプリをインストール済みの組織)。
   対象を選んで**Register**を押す。runtime bundle(約200MB)は最新の
   `runtime-*` GitHub Releaseから自動発見・自動インストールされます

   > [!WARNING]
   > Organization runnerは、[runner group](https://docs.github.com/ja/actions/hosting-your-own-runners/managing-self-hosted-runners/managing-access-to-self-hosted-runners-using-groups)
   > で許可リストを設定しない限り、**組織内の全リポジトリからジョブを受け付けます**。
   > Workflowのコードを実行する端末にとって、これはRepositoryスコープより明確に広い
   > 信頼境界です(Repositoryが既定なのはそのためです)。

   対象は後から変更できます。Runnerを停止して新しい対象を選ぶと、ボタンが
   **Re-register**に変わります。
5. 完了 — 以後はアプリを起動するだけでRunnerが自動スタートします
   (Start/Stopはダッシュボードのrunnerパネルにあります)

runtimeの取得元は`droidrunner.runtimeRepo`ビルドプロパティで指定したリポジトリです。
manifest URLの手動上書きは`advanced`にあります(GitHub Enterprise Serverや自前ホスト用)。
新しいbundleの公開は**Runtime bundle**ワークフローで行います(`runtime/README.md`参照)。

サインインはGitHub AppのDevice Flowを使うため、APKにclient secretは含まれず、
PATを手動で発行する必要もありません。userトークンはAndroid Keystoreで暗号化され、
Linux環境へは渡りません。Controllerが短時間有効な登録トークンへ交換します。
refresh tokenも同じ方式で保存し、期限切れ前に(そして401が返ればその時点でも)
サインインを更新するため、ephemeralな端末もアプリを開かずに再登録を続けられます。
更新自体が拒否された場合だけ通知で知らせます。

手動フォールバック(`advanced: manual PAT setup`)では、対象Repositoryの
Administration read/write権限を持つfine-grained PATを使えます(GitHub Enterprise
ServerやGitHub Appなしビルド向け)。

### GitHub Appの登録(セルフビルド向け)

Device FlowにはGitHub Appの登録が必要です(無料・サーバー不要・1回だけ):

1. GitHub → Settings → Developer settings → **New GitHub App**
2. Repository permissionで**Administration: Read and write**を付与。webhookは不要
3. **Device flow**を有効化
4. 任意: user access tokenの有効期限をオプトアウト(Optional features)する。
   アプリがrefresh tokenを保存し期限切れ前に更新するため、必須ではなく推奨にとどまる
5. 公開識別子を`gradle.properties`へ設定する:

```properties
droidrunner.githubAppClientId=Iv1.xxxxxxxxxxxxxxxx
droidrunner.githubAppSlug=your-app-slug
```

未設定の場合、アプリはGitHubログインを表示せず、手動PATセットアップだけを提供します。

## Runtime bundle

Linux環境はAPKへ直接含めず、初回セットアップ時に取得します。APKサイズを抑え、rootfsや
公式RunnerをAPKとは独立して更新するためです。コンパニオンアプリは必要ありません。

proot自体は**APK内のjniLibs**として同梱し、`nativeLibraryDir`から実行します。
Android 10以降はアプリのデータ領域にあるバイナリを`exec()`できないためです。
そのためダウンロードするbundleはデータのみです:

```text
bundle/
├── rootfs/            Ubuntu ARM64 base + Runner依存パッケージ
│   ├── bin/
│   └── usr/
└── home/
    └── runner/        公式Actions Runner(linux-arm64)
        ├── config.sh
        ├── run.sh
        └── bin/Runner.Listener
```

**Runtime bundle** CIワークフローがこれをビルドし、対応するmanifestと一緒に
GitHub Releaseへ公開します:

```json
{
  "version": "runner-2.337.0-ubuntu-24.04.3",
  "url": "https://github.com/OWNER/DroidRunner/releases/download/runtime-0.1.0/droidrunner-runtime-arm64.tar.gz",
  "sha256": "..."
}
```

SHA-256が保証するのは「アーカイブがmanifestと一致すること」だけで、**そのmanifestを誰が
書いたか**は分かりません。manifestを差し替えられる者は、CIジョブが実行されるrootfsを
自由に指定できてしまいます。そのためmanifestにも署名し、APKへ埋め込んだ公開鍵で
ダウンロード前に検証します。

署名鍵は一度作って安全に保管し、秘密鍵をリポジトリのsecret `RUNTIME_SIGNING_KEY` に
登録してください:

```bash
openssl ecparam -genkey -name prime256v1 -noout -out runtime-signing.pem
openssl ec -in runtime-signing.pem -pubout -outform DER | base64 -w0
```

出力された公開鍵を`gradle.properties`へ設定します:

```properties
droidrunner.runtimeSigningKeys=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...
```

カンマ区切りで複数の鍵を信頼できるので、鍵のローテーションが可能です(新しい鍵を配布 →
両方信頼される間はどちらで署名してもよい → 古い鍵を外す)。

鍵が設定されていないビルドは検証できないため、その旨をインストール時に表示します
(黙って受け入れることはしません)。鍵を持つビルドは、未署名・不正署名のmanifestを拒否します。

## NPU Device Agent

PRoot内のLinuxプロセスから、AndroidのNNAPIやベンダーJava APIを直接呼び出すことはできません。
そのため、NPUテストは次の境界で実行します。

```text
GitHub job
  └─ droidrunner-device CLI
      └─ 127.0.0.1 + job capability token
          └─ isolated Android Service
              └─ NNAPI / QNN / Neuron / ENN adapter
```

任意の`.so`をControllerへロードする設計にはしません。許可されたadapterを隔離Serviceで実行し、
モデル、入力、タイムアウト、出力先を明示したテスト要求だけを受け付けます。

Agentはloopback限定ですが、loopbackは端末上の他アプリとも共有されます。そのため二重に
制限しています: **ジョブ実行中しか応答せず**、capability tokenはジョブごとに発行して
終了時に失効します。トークンは環境変数ではなくアプリ専用ディレクトリ経由で渡します。

詳しい設計は[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)を参照してください。

## セキュリティ

self-hosted runnerは、Workflowに書かれたコードを端末上で実行します。

- 公開Repositoryのfork由来Pull Requestを自動実行しない
- RepositoryとPATの権限を最小化する
- `pull_request_target`とself-hosted runnerを安易に組み合わせない
- 個人利用中の端末ではなく、CI専用に初期化した端末を使う
- Runnerのwork directoryへ秘密情報を残さない
- 実行後にwork directoryを消去するephemeral運用を有効にする

PRootは実行互換レイヤーであり、DockerやVMのような強いセキュリティ境界ではありません。

## 制約

- Docker-based actionsとservice containersは利用不可
- PRootによるシステムコール変換のオーバーヘッドがある
- Androidの省電力機能やメーカー独自タスクキラーの影響を受ける
- 起動時自動スタートが効くのは、端末が**アンロックされてから**であり、起動した瞬間では
  ない。Androidはユーザーがcredential-lock状態のあいだ`BOOT_COMPLETED`を保留し、
  runtime bundleも保存済みの認証情報も、初回アンロックまで読めない
  credential-encrypted storageに置かれているため。したがってCI専用の端末には
  セキュアなロック画面を設定しないこと — さもなければ停電のあと、誰かが端末を手に取る
  までCIは止まったままになる。無人だった時間はダッシュボードに表示される
  ([#41](https://github.com/m96-chan/DroidRunner/issues/41))
- Android 12以降ではForeground Serviceの起動方法に制約がある
- 一部のActionsはARM64やAndroid上のLinux環境に対応していない
- rootfsとビルドキャッシュに大きなストレージを使用する可能性がある

## ロードマップ

- [x] GABEを参考にruntime bootstrapを実機で安定化
- [x] バッテリー・充電・温度・空き容量によるジョブ受付制御
- [x] リスナー異常終了時の復旧(バックオフ付き再起動)
- [x] ephemeral runnerとジョブ後クリーンアップ
- [x] Organizationスコープのrunner(1台で組織全体を担当)
- [x] per-job capability token付きDevice Agent
- [x] NNAPI capability probeとsmoke test — CIビルドごとに実機で実行
- [ ] 組み込みベンチマークではなく任意のモデルを実行する([#4](https://github.com/m96-chan/DroidRunner/issues/4))
- [ ] QNN / LiteRT / MediaTek Neuron / Samsung ENN adapter([#4](https://github.com/m96-chan/DroidRunner/issues/4))
- [x] runtime manifestの署名検証
- [x] Runner状態と保留理由を出す通知、およびGitHubには見えないことだけを伝える警告
- [x] スマホを他の用途で使いながらRunnerを見ておくPicture-in-Picture
- [x] ロックされていたせいで再起動後に無人だった時間を記録して表示する([#41](https://github.com/m96-chan/DroidRunner/issues/41))
- [ ] 1サンプルだけ条件に触れた程度でジョブを保留しない([#37](https://github.com/m96-chan/DroidRunner/issues/37))
- [ ] runtime bundleの更新通知・自動導入([#14](https://github.com/m96-chan/DroidRunner/issues/14))
- [ ] 複数端末の状態を表示する管理画面([#7](https://github.com/m96-chan/DroidRunner/issues/7))
- [ ] F-Droidでの公開([#18](https://github.com/m96-chan/DroidRunner/issues/18))
- [ ] GPL対応のruntime source archiveとSBOMを生成

## 参考プロジェクト

- [GitHub Actions Runner](https://github.com/actions/runner)
- [Godot Android Editor Build Environment (GABE)](https://github.com/godotengine/android-editor-buildenv-app)
- [PRoot](https://github.com/termux/proot)
- [UserLAnd](https://github.com/CypherpunkArmory/UserLAnd)

DroidRunnerは、特にGABEの「PRoot環境をAndroid Serviceとして管理する」という設計から
大きな影響を受けています。

## ライセンス

DroidRunnerは **GNU General Public License v2.0 only(`GPL-2.0-only`)** で公開しています
([`LICENSE`](LICENSE))。

APKには次のサードパーティ製コンポーネントも同梱され、それぞれのライセンスが適用されます。

| コンポーネント | ライセンス | 対応するソース |
| --- | --- | --- |
| [PRoot](https://github.com/termux/proot)(`libproot.so`とloader) | GPL-2.0 | [`runtime/build-proot.sh`](runtime/build-proot.sh)で固定したコミット、パッチは[`runtime/patches/`](runtime/patches) |
| [talloc](https://talloc.samba.org/)(prootへ静的リンク) | LGPL-3.0 | `runtime/build-proot.sh`で固定したバージョン |

runtime bundleにはさらに[GitHub Actions Runner](https://github.com/actions/runner)(MIT)と
Ubuntu rootfs(各パッケージのライセンス)が含まれます。bundleを配布する場合は、対応する
ソース、パッチ、ビルド手順、著作権表示を一緒に提供してください
(`runtime/build-bundle.sh`が中身を固定しています)。

同じ情報はアプリのAbout画面でも確認できます。
