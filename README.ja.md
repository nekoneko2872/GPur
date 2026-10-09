# GPur

[English](README.md) | [日本語](README.ja.md)

GPurは、Vulkanによる計算を選択できるPurpur 26.2のフォークです: https://github.com/nekoneko2872/GPur。

Paper/Purpurプラグインは通常のサーバースレッド上で動作します。GPUにはコピーした数値データを渡し、イベント、乱数、ワールドへの変更確定、依存する更新の順序はCPU側で維持します。

## ドキュメント

| 記録 | 日本語 | English |
| --- | --- | --- |
| 移行・実装範囲 | [日本語](docs/26.2-migration.md) | [English](docs/26.2-migration.en.md) |
| 実機・負荷検証 | [日本語](docs/26.2-validation.md) | [English](docs/26.2-validation.en.md) |
| GPur 1.1.0 検証記録 | [日本語](docs/1.1.0-validation.md) | [English](docs/1.1.0-validation.en.md) |
| 状態表示・エリトラ先読みの更新 | [日本語](docs/26.2-runtime-updates.md) | [English](docs/26.2-runtime-updates.en.md) |
| バニラ地形ノイズ補間 | [日本語](docs/26.2-vanilla-terrain.md) | [English](docs/26.2-vanilla-terrain.en.md) |
| 非同期地形処理・照合設定 | [日本語](docs/26.2-async-terrain.md) | [English](docs/26.2-async-terrain.en.md) |
| 廃止したカスタム地形設計の記録 | [日本語](docs/26.2-custom-terrain.md) | [English](docs/26.2-custom-terrain.en.md) |

## ビルド

GitチェックアウトとJDK 25を使用します。Windowsでは次のコマンドをそれぞれ実行します。

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat :purpur-server:test :purpur-server:createPaperclipJar
```

起動用JAR: `gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar`。ファイル名は`gradle.properties`の`mcVersion`と`gpurVersion`を組み合わせて生成します。ソースフォルダは`gpur-server`・`gpur-api`・`gpur-checkstyle`で、Gradleのプロジェクト名と`org.purpurmc`のAPIパッケージは維持しています。生成ソースはコミットせず、変更をパッチへ再構築します。

起動にはJDK 25と`--enable-native-access=ALL-UNNAMED`を使用します。`-dev.jar`と`gpur-bundler-*.jar`はビルド用の成果物です。実装範囲と検査は[移行・検証記録](docs/26.2-migration.md)を参照してください。

## 設定

既存の`gpur.yml`のキーを保持します。スキーマ2では、旧既定値のままの場合に限り、到達できなかったMob候補数の閾値を32から1、Anti-Xrayのセクション数の閾値を12から1へ移行します。ほかのカスタム設定は保持します。Purpur・Paper・プラグインの設定ファイル形式も維持します。

```yaml
gpu:
  multi-gpu:
    enabled: true
    devices: [auto]
  force: false
  timeout-ms: 100
```

デバイスの指定には、起動ログのUUID、正確なGPU名、`auto`を使用できます。物理UUIDでドライバーの重複エントリを除きます。独立したデバイスとして扱うため、異なるGPUをSLI/NVLinkなしで併用できます。マルチGPUを無効にすると、対応するGPUを1台使用します。未対応・失敗したデバイスではCPUへ戻します。

`force`は診断用に速度によるCPU優先を解除しますが、同一結果の検査や安全上の制限は解除しません。通常はディスパッチと読み戻しの時間を測定し、GPU計算が遅い場合は一時的にCPUを優先します。実行コンテキストの占有率は、GPUハードウェアの使用率を表すものではありません。

## バニラ地形ノイズ補間

GPur 1.1.0には、既存のバニラ地形生成器で行うノイズ密度補間だけを対象にした、範囲を制限した実験的Vulkan経路があります。初期値は無効です。`bukkit.yml`のgenerator設定や新しいワールドは不要です。既存・新規のバニラワールドは同じバニラ生成器とワールドデータ仕様を維持し、保存済みチャンク、シード、データパック、保存形式を置き換えません。

CPUが密度グラフを組み立て、実際の角の密度値を計算します。GPUはFP64とCPUと同じ演算順序で、範囲を制限したスラブ内の値を補間します。既定の厳密検証では、各GPUバッチをそのスラブ全体のCPU参照結果と比較します。チャンク全体をGPUで生成するわけではありません。密度・ノイズグラフ、乱数生成、ブレンディング、アクアファイア、地表ルール、洞窟、バイオーム、構造物、ブロック配置、ライティング、直列化、保存はCPU処理です。

この経路には`chunk-generation.vanilla-terrain.enabled: true`に加え、`chunk-generation.gpu-acceleration.enabled: true`と`chunk-generation.gpu-acceleration.terrain-enabled: true`が必要です。既定値は次のとおりです。

```yaml
chunk-generation:
  vanilla-terrain:
    enabled: false
    verify-every-batch: true
    max-interpolators: 16
    max-slab-values: 1048576
    minimum-values: 1024
    parity-interval: 128
```

GPUごとに使用するデバイスは既存の`gpu.multi-gpu.devices`で名前またはUUIDを指定します。厳密モードでは各バッチをCPU結果と照合します。`parity-interval`は`verify-every-batch: false`の場合だけ使います。1,024値未満のスラブはCPUで処理します。この新しい経路は実機・性能検証されていないため、制御された診断以外では厳密検証を無効にせず、`gpu.force: true`も高速化目的で設定しないでください。GPUを指定しただけでは地形処理がGPUに投入されたことを意味しません。

先読みは別のCPU負荷設定です。`chunk-generation.preloading.max-extra-distance`の初期値は`0`で、`2`にすると先読み範囲が広がり、チャンク読み込み・生成・送信のCPU負荷が増える場合があります。GPU補間を有効にする設定ではありません。詳細は[バニラ地形ノイズ補間ガイド](docs/26.2-vanilla-terrain.md)を参照してください。

マーカーのないワールドに`gpur:terrain-v1`を新しく設定する方式は廃止され、起動時に理由を示して失敗します。バニラ地形に戻すには、そのgenerator設定を削除するか、`server.properties`の`level-name`を読み込みたい既存のバニラワールド名に戻します。たとえば、既存ディレクトリ名が`gpur`なら`level-name=gpur`を指定し、`bukkit.yml`に`worlds.gpur.generator`があればその設定を削除します。ワールドディレクトリやマーカーファイルは削除しないでください。`gpur-terrain.properties`があるワールドは、GPU設定が無効でも従来のカスタムCPUルールで生成を続けます。マーカーを削除・編集してワールドを変換しないでください。詳細は[廃止したカスタム地形の設計記録](docs/26.2-custom-terrain.md)を参照してください。

旧設定`chunk-generation.custom-terrain.enabled`は、ワールドを選択せず、バニラ補間経路も有効にしません。範囲を制限したバニラ経路には`chunk-generation.vanilla-terrain.enabled`を使用します。

1.1.0の起動用JARは`gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.1.0.jar`です。過去の1.0.0実機・負荷測定は当時のビルドと負荷条件について引き続き有効ですが、新しい補間経路の検証ではありません。新たな性能向上や対応ハードウェアは主張していません。

## 動作状況の確認

`/gpur`または`/gpur status`は、GPUごとに項目をまとめた色付きの概要を表示します。プレイヤー距離計算とAnti-Xrayを名前で区別し、結果未記録・GPU有効・再試行までのCPU退避時間・停止・設定無効を示します。GPU結果カウンターは採用済みの計算バッチ数であり、完成チャンク数・プレイヤー数・tick数・GPUハードウェア使用率ではありません。先読み範囲と飛行時の速度倍率も別に表示します。行にマウスを重ねると意味を確認できます。`/gpur status detail`では、ミリ秒単位の処理時間、CPU参照の照合回数、CPU退避履歴、UUID、GPU受付見送り回数を表示します。どちらも`gpur.command`権限が必要です（既定ではOP）。

GPUが使用可能でも、処理中とは限りません。結果の件数は起動・再読み込み以降に採用したGPU計算バッチ数であり、プレイヤー数・tick数・適用済みパケット数ではありません。処理中の件数は実行枠の占有数で、GPU使用率ではありません。CPUへの切り替え・GPU受付見送りの件数も、CPUで行ったすべての計算を数えるものではありません。`/gpur reload`で件数をリセットし、デバイスを再検査します。

## エリトラの先読み

新規設定では、Paperの既存のチャンク範囲内で飛行方向を優先します。`chunk-generation.preloading.max-extra-distance`の初期値は`0`、`chunk-generation.preloading.elytra-throughput-boost.enabled`は`false`です。既存の明示的な設定値は保持します。旧設定の追加範囲`3`・速度倍率有効を使用している場合は、tickの安定を優先するなら`0`・`false`へ変更してください。追加範囲は全方向のチャンク処理を増やし、速度倍率はCPUの読み込み・生成・送信量を増やします。先読み設定はGPU地形補間を有効にしません。詳しくは[バニラ地形ノイズ補間ガイド](docs/26.2-vanilla-terrain.md)を参照してください。

高性能サーバーでは、実際のエリトラ飛行中に生成並列数を増やす設定を任意で有効にできます。

```yaml
chunk-generation:
  preloading:
    max-extra-distance: 2
    elytra-throughput-boost:
      enabled: true
      max-extra-concurrent-generates: 8
      concurrent-generates-multiplier: 2.0
```

並列数の上限は初期値`8`（`0..64`）で、生成要求レートの倍率とは別です。CPU上の生成並列数を増やす設定です。対象チャンクは別の`max-extra-distance`設定以外では変わらず、TPSの保証やGPU地形設定の変更にはなりません。並列数の計算式とPaperの上限値の扱いは[バニラ地形ノイズ補間ガイド](docs/26.2-vanilla-terrain.md)を参照してください。

方向による優先度はキューの再構築までキャッシュし、キュー内の優先順位に影響しない速度変化では再構築しません。方向先読みが動作していないときはPaperの元の比較処理を使います。先読みの追加負荷を減らす変更であり、新規地形の生成中に常時20 TPSを保証するものではありません。

## 計算の方針

- 距離計算はFP64を使用し、Javaの演算順序を維持します。積和の融合は使用せず、各デバイスが起動時の同一結果検査を通過する必要があります。
- Anti-Xrayのマスク計算はPaperのHIDE方式に対応します。変更確定前にパケット全体を別バッファへ書き、定期的にPaperの処理結果と全バイトを比較します。ランダム置換方式や特殊な非遮蔽ブロック設定ではPaperのCPU実装を使用します。Paper側で無効にしたワールドのAnti-XrayをGPurが有効化することはありません。
- `gpur:terrain-v1`の新規選択は廃止されています。マーカーがある既存ワールドは従来のカスタムCPUルールを維持します。任意のバニラ経路は範囲を制限した密度スラブを補間するだけで、残りのバニラ地形生成はCPUで行います。
- Mob AI、移動の状態変更、プラグインのコールバック、依存するレッドストーン更新は、Paperの挙動を維持しながら並列tickとしてGPUへ渡すことはできません。CPU参照で同一結果を検証した独立した計算だけをGPUへ移します。

Vulkan 1.1、コンピュートキュー、FP64シェーダー対応、ホストから見えるcoherentメモリが必要です。GTX 1080とRTX 3070は実機の計算検査を通過しています。それより古いGPUは認定していません。利用できるデバイスは起動時に検査します。Vulkanへの対応だけで高速化が保証されるわけではありません。

## 検証目標

各地に分散する300人のSMPは負荷検証の目標であり、処理できる人数の保証ではありません。同じシード・設定から毎回新規ワールドを作り、同じハードウェアで、p50/p95/p99 MSPT、チャンク読み込み・生成遅延、GC、プラグイン、CPU→GPU→CPUの全費用を比較します。GPU計算の同一結果検査だけでは、サーバー全体の性能や全プラグインの互換性を証明できません。

[実機・負荷検証記録](docs/26.2-validation.md)には、CPU・RTX 3070・GTX 1080・混在構成の試験を収録しています。安定性の目標は**未達**です。公開済みの大規模負荷試験に使ったビルドでは300人接続・60人移動・AI Mob 3,000体で約4 TPSでした。再試行制御と結果採用の修正前に行った300人全員の連続移動試験では、watchdogによる終了が発生しました。通常のPaper/Purpurよりサーバー全体が高速になったことは立証していません。

暫定的な完成基準はCPU構成と同等以上の性能です。各構成1回の測定では、RTX 3070で改善した指標がある一方、GTX 1080は固定AI区間で遅く、混在構成ではp95/p99 MSPTが悪化しました。全構成でCPUと同等以上の性能があるとは立証できていません。

## 上流とライセンス

Purpur 26.2と固定したPaperリビジョンをベースにしています。必要なライセンス・著作権表記・パッチの由来は保持します。上流プロジェクトの貢献者・支援者一覧や寄付リンク、IDE情報、旧版の未適用パッチ、ローカルのサーバーワールド・ログは除外します。依存関係のlockfileメタデータは保持します。[LICENSE](LICENSE)と[NOTICE.md](NOTICE.md)を参照してください。
