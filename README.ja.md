# GPur

[English](README.md) | [日本語](README.ja.md)

GPurは、Vulkanによる計算を選択できるPurpur 26.2のフォークです: https://github.com/nekoneko2872/GPur。

Paper/Purpurプラグインは通常のサーバースレッド上で動作します。GPUにはコピーした数値データを渡し、イベント、乱数、ワールドへの変更確定、依存する更新の順序はCPU側で維持します。

## ドキュメント

| 記録 | 日本語 | English |
| --- | --- | --- |
| 移行・実装範囲 | [日本語](docs/26.2-migration.md) | [English](docs/26.2-migration.en.md) |
| 実機・負荷検証 | [日本語](docs/26.2-validation.md) | [English](docs/26.2-validation.en.md) |

## ビルド

GitチェックアウトとJDK 25を使用します。Windowsでは次のコマンドをそれぞれ実行します。

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat :purpur-server:test :purpur-server:createPaperclipJar
```

起動用JAR: `gpur-server/build/libs/gpur-server-26.2-SNAPSHOT1.0.0.jar`。ファイル名は`gradle.properties`の`mcVersion`と`gpurVersion`を組み合わせて生成します。ソースフォルダは`gpur-server`・`gpur-api`・`gpur-checkstyle`で、Gradleのプロジェクト名と`org.purpurmc`のAPIパッケージは維持しています。生成ソースはコミットせず、変更をパッチへ再構築します。

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

## 計算の方針

- 距離計算はFP64を使用し、Javaの演算順序を維持します。積和の融合は使用せず、各デバイスが起動時の同一結果検査を通過する必要があります。
- Anti-Xrayのマスク計算はPaperのHIDE方式に対応します。変更確定前にパケット全体を別バッファへ書き、定期的にPaperの処理結果と全バイトを比較します。ランダム置換方式や特殊な非遮蔽ブロック設定ではPaperのCPU実装を使用します。Paper側で無効にしたワールドのAnti-XrayをGPurが有効化することはありません。
- 旧FP32補間と代替地形生成はMinecraftの地形結果を変えるため使用しません。旧設定は移行用に読み込めます。
- Mob AI、移動の状態変更、プラグインのコールバック、依存するレッドストーン更新は、Paperの挙動を維持しながら並列tickとしてGPUへ渡すことはできません。CPU参照で同一結果を検証した独立した計算だけをGPUへ移します。

Vulkan 1.1、コンピュートキュー、FP64シェーダー対応、ホストから見えるcoherentメモリが必要です。GTX 1080とRTX 3070は実機の計算検査を通過しています。それより古いGPUは認定していません。利用できるデバイスは起動時に検査します。Vulkanへの対応だけで高速化が保証されるわけではありません。

## 検証目標

各地に分散する300人のSMPは負荷検証の目標であり、処理できる人数の保証ではありません。同じシード・設定から毎回新規ワールドを作り、同じハードウェアで、p50/p95/p99 MSPT、チャンク読み込み・生成遅延、GC、プラグイン、CPU→GPU→CPUの全費用を比較します。GPU計算の同一結果検査だけでは、サーバー全体の性能や全プラグインの互換性を証明できません。

[実機・負荷検証記録](docs/26.2-validation.md)には、CPU・RTX 3070・GTX 1080・混在構成の試験を収録しています。安定性の目標は**未達**です。最終版では300人接続・60人移動・AI Mob 3,000体で約4 TPSでした。再試行制御と結果採用の修正前に行った300人全員の連続移動試験では、watchdogによる終了が発生しました。通常のPaper/Purpurよりサーバー全体が高速になったことは立証していません。

暫定的な完成基準はCPU構成と同等以上の性能です。各構成1回の測定では、RTX 3070で改善した指標がある一方、GTX 1080は固定AI区間で遅く、混在構成ではp95/p99 MSPTが悪化しました。全構成でCPUと同等以上の性能があるとは立証できていません。

## 上流とライセンス

Purpur 26.2と固定したPaperリビジョンをベースにしています。必要なライセンス・著作権表記・パッチの由来は保持します。上流プロジェクトの貢献者・支援者一覧や寄付リンク、IDE情報、旧版の未適用パッチ、ローカルのサーバーワールド・ログは除外します。依存関係のlockfileメタデータは保持します。[LICENSE](LICENSE)と[NOTICE.md](NOTICE.md)を参照してください。
