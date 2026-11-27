# 視認候補の最適化

## 変更

`Perception.update` は視認判定を最大64件まで行います。以前は全候補を距離で安定ソートしてから、先頭64件だけを使用していました。

近傍64件を保持する再利用可能なヒープへ置き換え、全候補のリスト・距離配列・ボックス化した整数配列・ソート結果リストの生成を省きました。同距離では追加順を優先し、以前の先頭64件と一致させます。遠距離候補の記憶には全対象を残します。

観測頻度、距離計算、視野、明るさ、遮蔽、記憶期間、学習の更新頻度は変更していません。候補を減らすためにAIの判断回数を間引く変更もありません。

候補数をn、上限をk=64とすると、選別・整列はO(n log k + k log k)、作業領域はO(k)です。作業領域はクローンごとに一度確保し、毎回再利用します。処理後にエンティティへの参照を解放します。

## 再現方法

```sh
./gradlew test
./gradlew benchmarkPerception
./gradlew build
```

ベンチマークはJava 17で実行します。固定シードで生成した16パターンの入力を使い、旧処理と新処理が同じ64件を同じ順序で返すことを確認します。ウォームアップ後、計測順を交互に変えて5回計測し、中央値を出力します。割り当て量はJDKのThreadMXBeanで取得します。

## 計測結果 (2026-11-18)

環境: OpenJDK 17.0.21、共有Linux実行環境、Gradle 8.8。時間は1回の候補選別、割り当て量は1回あたりの一時割り当てです。

| 候補数 | 旧処理 | 最適化後 | 速度比 | 旧割り当て | 最適化後の割り当て |
|---:|---:|---:|---:|---:|---:|
| 16 | 426 ns | 315 ns | 1.35倍 | 544 B | 0 B |
| 64 | 2,723 ns | 2,599 ns | 1.05倍 | 2,280 B | 0 B |
| 256 | 23,994 ns | 9,830 ns | 2.44倍 | 11,416 B | 0 B |
| 1,024 | 133,631 ns | 20,201 ns | 6.62倍 | 49,048 B | 0 B |

これは候補選別だけのマイクロベンチマークです。ワールドのエンティティ検索、光線による遮蔽判定、ブロック探索、サーバー全体のtick時間、FPSは計測対象に含みません。測定値はJIT・GC・共有環境の負荷によって変動します。特に少数候補では時間差が小さく、改善の中心は一時割り当ての削減です。クローン生成時の固定作業領域の確保は0 Bの数値に含みません。

## 正確性の検証

- JUnit全17件成功(うち候補選別4件)、失敗0件。
- 同距離・昇順・降順・ランダム入力、64件超、重複候補、再利用、NaN・無限大・符号付きゼロを全件安定ソートと比較。
- MOD全体のコンパイル・JAR生成成功。
- 実サーバーのGameTestと長時間ソークは未実施。ゲーム全体の速度改善率は未計測。


# 多数クローン向けの重複計算削減 (2026-11-19)

## 変更と維持する動作

- 階段の形状判定を、全段・全横列の走査から座標の直接計算へ変更。段数に比例する処理をなくし、計算中のBlockPos生成も省略する。
- 次元判定を維持し、end/finishedを毎回読むため掘り進み・完成の変更を即時に反映する。ワールド状態を古いまま保持するキャッシュは使わない。
- 観察イベントの同じ個体・同じ時間範囲の一覧を、読み取り専用スナップショットとして共有する。キャッシュは個体ごとに4範囲まで。新しいイベントを記録した直後(同じtickを含む)、削除・prune・clearの際に無効化/解放する。
- 観察対象、観察範囲、イベントの保持数・期間、報酬計算、各個体のQ学習更新、移動・戦闘・観察の頻度は変更しない。観察対象を記録する集合も毎回作り直さず再利用する。

## 再現方法と条件

```sh
./gradlew test
./gradlew benchmarkCrowd
./gradlew runGameTestServer -Drlclones.gameTestNamespaces=rlclones_crowd,rlclones_civic,rlclones_stair_repair
```

Java 17.0.21 / Gradle 8.8 / 共有Linux環境で計測。ウォームアップ後、旧処理と新処理の計測順を交互に入れ替え、5回の中央値を取る。割り当て量は現在のスレッドのThreadMXBeanで取得する。

**模擬人数に比例する部品処理のバッチであり、実体のクローンをその人数だけ動かしたFPS・サーバーtick時間ではない。**

- 階段: 16本・128段の記録を使用。1体相当につき4地点で通常/拡張通路/足場の3判定を行う。旧走査と同じ結果になることを確認する。旧実装はテスト専用の比較基準として保持する。
- 観察イベント: 1体につき8観察者が3範囲(直近40/20/4tick)を読む。各バッチで全対象へ新しいイベントを追加してキャッシュを無効化するため、変更のないデータを読み続けるだけの比較ではない。各個体のログは1tick1イベント、保持201件で定常化し、旧/新で同じ件数を処理する。

## 結果

時間・一時割り当て量は**1バッチあたり**。階段判定の旧実装は深い階段を何度も走査し、大量の一時オブジェクトを生成する条件である。

| 部品 | 模擬人数 | 旧時間 | 新時間 | 速度比 | 旧割り当て | 新割り当て |
|---|---:|---:|---:|---:|---:|---:|
| 階段形状判定 | 50 | 20.756 ms | 0.041 ms | 507.24倍 | 103,081,998 B | 6 B |
| 階段形状判定 | 150 | 54.396 ms | 0.095 ms | 574.30倍 | 312,535,014 B | 6 B |
| 階段形状判定 | 300 | 106.737 ms | 0.192 ms | 556.54倍 | 626,596,062 B | 6 B |
| 観察イベント取得 | 50 | 0.640 ms | 0.140 ms | 4.56倍 | 392,404 B | 89,604 B |
| 観察イベント取得 | 150 | 2.583 ms | 0.501 ms | 5.15倍 | 1,177,204 B | 268,804 B |
| 観察イベント取得 | 300 | 3.720 ms | 0.802 ms | 4.64倍 | 2,354,404 B | 537,604 B |

観察イベント取得の一時割り当て量は、この条件で約77%減少した。キャッシュとして残る領域は測定対象の一時割り当てと別に存在し、個体ごとに4範囲へ制限している。階段形状判定の残り数バイトは計測バッチに混入する微小な割り当てで、判定本体はオブジェクトを生成しない。

ゲーム全体の改善率は、実際の階段数・地形・戦闘・他MOD・描画負荷によって変わる。階段がほぼない場面や、他者を観察していない場面では、この部品の改善効果も小さい。

## 正確性と実サーバーの回帰検証

- 単体37件成功。全4方向・段数0/1/16/150・完成状態・通路幅・境界・重複する階段を旧走査と比較。
- イベント共有は範囲境界、同tickの追加、prune、256件上限、4範囲を超える入替、clear、読み取り専用性を検証。
- 実サーバー16件成功。模倣学習・観察による敵知識・人数制限・動的な階段/次元判定4件、階段補修6件、文明支援/倉庫配送6件。
- MODビルド成功。長時間ソークとユーザーのMOD構成でのFPS比較は未実施。

## 焚き火探索 (2026-11-19)

探索範囲は横±16・縦±4の9,801座標。同じ距離の場合は従来のx→y→zの走査順を保って距離順に一度だけ並べ、点火済み焚き火が見つかれば終了する。毎回ワールドの現状を確認し、追加・破壊・消火をまたぐ結果キャッシュは使わない。座標カーソルは探索中に再利用する。

以下は原点からX方向の記載距離に焚き火が一つある場合の `getBlockState` 呼び出し数。処理時間やゲーム全体のFPSの計測ではない。

| 距離 | 従来 | 変更後 |
|---|---:|---:|
| 2 | 9,801 | 31 |
| 4 | 9,801 | 255 |
| 8 | 9,801 | 1,596 |
| 16 | 9,801 | 7,020 |
| 範囲内になし | 9,801 | 9,801 |

`NearbyOffsetsTest` で全座標と同距離順の一致、固定シード100通りの配置の最寄り結果、近傍での探索打ち切りを確認する。`CampfireTests` では実ワールドで同じtick内の消火・再点火・ソウル焚き火の発見を確認する。

## 対象を含まない区画の除外 (2026-11-19、今回の変更)

前版`12b54a3`の距離順探索を比較基準にし、さらに16×16×16の区画パレットで点火済み焚き火を含まない区画を除外する。近い32座標は直接読み、近距離ではフィルターを初期化しない。パレットに対象が含まれない領域では、候補の実ブロック確認は9,801回から32回と最大18回の区画判定になる。ブロック削除後もパレットに古い状態が残る場合は詳細確認が増えるが、結果は変わらない。

地下の近傍鉱石探索にも同じ仕組みを使う。鉱石を含む可能性のある区画では元と同じブロック・開放面・視野・遮蔽判定を行う。対象が多い区画では削減効果が小さく、区画フィルターの確認コストも生じる。保持する判定は走査の間だけで、更新頻度や探索範囲を減らさない。

実サーバーの同一座標で焚き火なしの探索を反復。50回のウォームアップ後、計測順を交互にした5回の中央値（Java 17、Forge 47.4.0、共有Linux環境）。人数そのものの負荷試験ではなく、記載回数の探索処理の合計時間。

| 探索回数 | 前版の距離順探索 | 今回の区画除外付き |
|---|---:|---:|
| 50 | 17.284 ms | 5.927 ms |
| 150 | 50.070 ms | 16.154 ms |
| 300 | 103.235 ms | 34.989 ms |

300回の該当処理で約66%短縮。2ブロック先の焚き火ありでも比較し、両実装とも300回合計1ms未満だった（サブミリ秒測定は揺らぎが大きいため高速化率を主張しない）。ゲーム全体のFPS、MSPT、動かせる人数の増加率を示す数値ではない。

再現: `./gradlew runGameTestServer -Drlclones.gameTestNamespaces=rlclones_sections`。`SECTION-BENCH`行に比較結果が出る。対象なし・対象あり・負座標と区画境界・上下の高さ境界・同tick内の設置/破壊/消火を確認。今回の回帰検証はJUnit 40件と専用GameTest 34件成功。

## Primitive-array nearest candidate workspace (2026-11-27)

The bounded heap keeps exactly the same candidate limit, double ordering, tie
order and reference lifecycle. The 64 separate Entry objects are replaced by
arrays of values, distances, insertion sequences and heap slot indices.

A standalone Java check compiled the exact new source with `javac --release 17`
and ran on OpenJDK 21.0.12.1. Its Git blob was
`c06bf76b61815aa7a90b2db5e47566fb505c6d61`.
The fixed-seed test ran 12,000 batches and compared 409,373 retained references
against stable full sorting, with no mismatch. The equivalent regression test
is now in NearestCandidatesTest.

A standalone microbenchmark compared the new source with a transcription of
the previous Entry-object heap algorithm. Allocation was measured using
ThreadMXBean over 10,000 constructions, including the outer result reference
array (~4 bytes per instance); median timings were taken over five alternating
20,000-scan runs after warmup, with 32 fixed-seed inputs.

| Input candidates | Previous ns/scan | New ns/scan |
|---:|---:|---:|
| 16 | 249.8 | 243.2 |
| 64 | 1528.7 | 1280.8 |
| 256 | 5121.7 | 4816.8 |
| 1024 | 23041.8 | 20218.8 |

Initial allocation per 64-slot workspace: approximately 2356 -> 1652 bytes
(29.9% reduction, including the outer reference array).
These are component measurements on Java 21, not Minecraft FPS/MSPT or Java 17
runtime measurements. Forge build and full GameTest results are not established
by this standalone check.

## Standalone regression validation (2026-11-27)

Compiled exact GitHub source blobs with `javac --release 17` and ran an
independent Java harness on OpenJDK 21.0.12.1. Local `git hash-object` results
matched each blob:

- ReplayBuffer: `37a501216515537c99ec28a7f14fac42e468ffcc`
- StairFootprint: `055fa65d7b6a689e3ffacbfe6fb5b7c81cf804d5`
- RevisionList: `82d23e956f7e01dd64b6d21c74af49a94f524934`

All checks passed:
- 34,436 replay samples compared against fixed-capacity ring reference with equal RNG seeds.
- 283 byte comparisons against original-format reference writer or save/load round trips.
- 280,000 stair/shaft proximity comparisons against the original per-step formula, covering four directions and seven lengths.
- 10,000 randomized list edits compared with ArrayList, including revision increments.

These checks do not compile or exercise Minecraft-dependent integration code,
and they do not establish Forge build success or population MSPT. Draft PR #3
is for CI validation only; do not merge on the strength of standalone checks.
Direct GitHub git access from the validation container failed DNS resolution.
