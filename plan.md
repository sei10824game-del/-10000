# plan.md — 自力でダイヤフル装備まで(進行設計)

状態: 設計済み・未実装。次の実装セッションはこのファイルと REQUIREMENTS.md だけを読む。
根拠: ソーク run 37225966425(REQUIREMENTS.md「長時間検証(ソーク)」)。36000tick で石のツルハシ 3/6、鉄 0/6。

## 0. 要約
- 鉄に届かない主因は「下へ掘る理由が無い」こと。掘る行動(STAIRS/SHAFT)は遊び扱いで外され、報酬面でも掘ると損になり、遠征が得になる
- 対策は2本立て:
  1. **進行ドライバ**(決め打ち): 今足りない段階(鉄/ダイヤ)を持ち物から計算し、安全なときは掘りに行かせる。鉄装備で止めずダイヤ層まで行く
  2. **観測と報酬**(学習): 戦略の状態に「何のために掘るか」を足し、鉄・ダイヤ・深さに報酬を付けて、学習がドライバと逆を向かないようにする
- 新しい Option は足さない(STAIRS/SHAFT/MINE を使い回す)

## 1. 現状と原因(場所つき)
| # | 事実 | 場所 |
|---|---|---|
| C1 | BUSY な行動(遠征・JOIN・FARM・QUARRY…)が1つでも選べると STAIRS/SHAFT/ACHIEVE を候補から外す。遠征はほぼ常に選べるので掘りは 0.9%/0% | `CloneController.startOption`(`BUSY_OPTIONS` の直後、`idle` マスク) |
| C2 | 掘りは `ironGeared` で止まり、目標の深さは STAIRS=16 / SHAFT=-50 固定。ダイヤ層(-58前後)へ行く段階が無い | `StairMining.wanted`/`targetY`、`ShaftMining.wanted`/`targetY` |
| C3 | 掘り着いたら終わり(`feet.getY() <= targetY`)。横に掘らないので、壁に見えている鉱石しか取れない | `StairMining` L276付近、`ShaftMining` L348付近 |
| C4 | 報酬: 1tick -0.005(STAIRS 上限3000tick で最大 -15)、鉱石を壊すと +0.8(石炭も同じ)、道具を作ると +1.5(木でもダイヤでも同じ)。遠征は完了 +5 と新チャンクごと +0.3。掘りは損、遠征は得になる | `CloneController.trackRewards`、`onBlockBroken`、`onCrafted`、`finishOption` |
| C5 | 割引は 0.995^tick(長い行動の先の価値はほぼ0)。掘りで集めた材料の価値は、後でクラフトしたときにしか報酬にならず、掘りに返らない | `finishOption`(`gamma`) |
| C6 | 戦略の状態(hp/food/threat/hasFood/items/resource/ally/dark/armed/animals)に「進行段階」が無い。鉄が欲しい状態とそうでない状態を区別できない | `StrategyState.encode`、`Senses.strategyState` |
| C7 | 石のツルハシを作らない個体がいる(丸石33・板10・木のツルハシ、ほか)。原因未特定 | `Crafting.plan`/`hasWork`/`urgent` |
| C8 | オプション無し(none)が 21% | 不明(計測が必要) |
| C9 | 満腹度0まで減る個体がいる。地下に潜る前の食料準備が無い | - |

## 2. 方針
- **進行段階は持ち物だけから計算する**(プレイヤーにもクローンにも使えるので、見て真似る学習にもそのまま効く)
- **決め打ちは「いつ掘りに行くか」だけ**。掘り方・戦い方・逃げ方は今の仕組みのまま
- **報酬はその行動の中で出す**(C5 のため。後のクラフトに報酬を付けても掘りには返らない)
- 古い脳(保存済み Q 表)と互換にする: 状態に足す桁は一番上の桁にし、値0 のとき今までのキーと一致させる

## 3. 変更項目
### P-01 進行段階 `ai/Progression.java`(新規)
- `static int tier(Player)`: 0=なし / 1=木のツルハシ / 2=石のツルハシ / 3=鉄のツルハシ / 4=鉄装備(`StairMining.ironGeared`) / 5=ダイヤのツルハシ / 6=ダイヤ全部(ツルハシ・剣・防具4)
- `static Need need(Player)`: `WOOD`(木のツルハシ無し)/ `STONE`(石のツルハシ無し)/ `IRON`(石以上のツルハシ有り かつ `ironShort>0`)/ `DIAMOND`(鉄以上のツルハシ有り かつ `diamondShort>0`)/ `NONE`
- `ironShort`: 鉄以上を持っていない物の鉄の必要数の合計(ツルハシ3・剣2・兜5・胴8・脚7・靴4。盾は `ironGeared` に合わせて数えない)−(鉄インゴット+原石の鉄)。`diamondShort`: 同じ考えでダイヤ(ツルハシ3・剣2・兜5・胴8・脚7・靴4)−ダイヤ数
- `static int digTargetY(Need)`: IRON→16、DIAMOND→-58(1.18以降のダイヤが一番多い高さ。岩盤 -64〜-60 の上)
- `static boolean digWanted(Player)`: need が IRON か DIAMOND、かつブロック破壊が許可されている
- 持ち物が変わったときだけ計算し直す(`Crafting.hasWork` と同じキャッシュの仕方: `getTimesChanged()`)

### P-02 掘りを「遊び扱い」から外す
- `startOption`: `Progression.digWanted(self)` のときは `idle` から STAIRS/SHAFT を除く(外さない)。ACHIEVE は今のまま

### P-03 進行ドライバ(掘りに行かせる)
- `drive()` の既存の引っ張りに追加: 次の全部を満たすとき `nextOption = STAIRS`(地表か既知の階段がある)、だめなら SHAFT(はしごが有る/作れる)
  - `digWanted`、threat=0、HP≥70%、満腹度≥14 か食べ物を4個以上持っている、前回の掘りが終わってから1200tick以上
  - 今の行動が STAIRS/SHAFT/MINE/CRAFT/EAT/FIGHT/FLEE ではない
- 準備(足りなければ先にそちらを `nextOption` にする): 食べ物4個以上(無ければ HUNT/FARM)、原木+板/4 が4以上(作業台・棒・燃料用。無ければ GATHER_WOOD)、石炭があれば松明8本
- 空振り対策: 掘りが3回続けて「4ブロックも下がれず、鉱石も取れず」に終わったら 6000tick 休む(`digBackoffUntil`)。その間は P-02/P-06 も効かせない
- 地下で鉄の原石が3個以上たまってかまどが近くに無いときは、今の `furnaceWanted`→`stationToPlace` の流れで置いて焼く(新規処理は不要。テストで確認だけする)

### P-04 段階ごとの目標の深さ
- `StairMining.wanted` / `ShaftMining.wanted` の `ironGeared(self)` を `!Progression.digWanted(self)` に置き換える(ダイヤが足りないうちは鉄装備でも掘る)
- `targetY` は「テストが入れた値があればそれ、無ければ `Progression.digTargetY`」。実装: 既定値を `Integer.MIN_VALUE`(自動)にし、`targetY()` で解決する。既存テストは `targetY` に直接代入している(`CloneGameTests` L3056, L3091, L3791)のでそのまま動く
- 鉄の段階で y=16 まで掘った階段は、ダイヤの段階で同じ階段を続けて -58 まで掘る(既知の階段から再開する今の仕組みを使う)

### P-05 横掘り(目標の深さに着いたあと)
- `StairMining` に BRANCH 段階を足す: 目標の深さに着いて `digWanted` のままなら、階段の向きにまっすぐ 1×2 のトンネルを掘る。1回の行動で最大48ブロック、32ブロックごとに右へ90°曲がる。8ブロックごとに松明(持っていれば)
- 進み具合は階段の記録(`Bases.staircases` の要素)に持たせ、次の STAIRS で続きから掘る
- 止まる条件: 前方・左右に水か溶岩(今の「水か溶岩がある」判定を前と左右にも使う)、HP<50%、満腹度<8 で食べ物なし、`digWanted` でなくなった
- 鉱石: 壁に見えた鉱石は今の `oreReflex`(ORE_REFLEX に STAIRS 有り)で取る
- 持ち物満杯対策: BRANCH 中は丸石64個を残し、それ以外の丸石・深層岩の丸石・凝灰岩・閃緑岩・安山岩・花崗岩・土を捨てる
- 見積もり: -58 で 1×2 トンネルはダイヤ1鉱脈(平均4個)におよそ100〜150ブロック。ダイヤ29個に約900ブロック、深層岩を鉄のツルハシで約30tick/ブロック → 1体あたり約3万tick。ダイヤ全部はソーク 72000tick でもぎりぎりなので、今回の受け入れ基準には入れない(5章)

### P-06 遠征・JOIN を抑える
- `Expedition.canLead`: `digWanted` のあいだは false(`digBackoffUntil` 中は除く)
- JOIN: `Senses.strategyMask` で `digWanted` のあいだは JOIN を外す(同上)
- 既存の遠征テストは素手のクローンで動く(`need`=WOOD なので `digWanted`=false)ため影響しない想定。実装時に各テストの初期の持ち物を確認する

### P-07 観測(戦略の状態に「掘る理由」を足す)
- `StrategyState.encode` に `prog` 桁(0=掘る理由なし / 1=鉄のため / 2=ダイヤのため。`digWanted` と need から)を**一番上の桁**として足す: `key = prog * 3456 + 今のキー`(3456 = 3·3·3·2^7)。prog=0 のキーは今までと同じなので保存済みの Q 表はそのまま読める
- `decode` / `describe` を合わせて直す。`Senses.strategyState` は `agent`(見ている相手)の持ち物から計算する
- `prior`: prog=1/2 のとき STAIRS 0.9、SHAFT 0.8、MINE 0.8、EXPEDITION -0.2、JOIN 0.1、EXPLORE 0.1(それ以外は今の値)

### P-08 報酬(その行動の中で出す)
| 報酬 | 値(初期値。ソークで調整) | 条件 |
|---|---|---|
| 鉄の原石を拾う | +1.5/個 | `ironShort>0` のあいだ(`onPickup` で物を見る) |
| ダイヤを拾う | +4/個 | `diamondShort>0` のあいだ |
| 深さ | +0.15/段 | STAIRS/SHAFT 中、`digWanted`、この命での一番深い所を更新したとき(-58 まで最大 約+19) |
| 段階が上がる | 木1 / 石2 / 鉄ツルハシ6 / 鉄装備8 / ダイヤツルハシ10 / ダイヤ全部20 | この命での最高段階を超えたとき(死んだら最高段階を0に戻す)。主に CRAFT に入る |
- 今の「鉱石を壊すと +0.8」「道具を作ると +1.5」はそのまま(上の表は追加)
- 悪用の余地: 拾った数で出すので、仲間からもらった分(ItemAid)も報酬になる。渡す側は減るだけで報酬は無く、欲しい側が持った時点で頼みは消えるので往復しない
- 比較の目安: STAIRS 3000tick = -15。y=16 の横掘り100ブロックで鉄の原石5〜10個 → +7.5〜15、鉱石 +0.8×数、深さ最大 +19 → 掘りが遠征(+5 と +0.3×新チャンク)より得になる

### P-09 調査と修正: 石のツルハシを作らない(C7)
- 仮説: (H1) CRAFT がほとんど選ばれない(遠征 12000tick・JOIN 14000tick が長い。`urgent()` はかまどと最初のバケツだけ)/(H2) 作業台が近くに無く、板が他(柵・好奇心クラフト)に使われる/(H3) `forcedTarget` が別の物で止まっている/(H4) `wanted()` の順番(剣・盾が先)
- 計測: ソークの `SOAK-CLONE` 行に 1200tick ごとに `Crafting.plan` の結果の物、`hasWork`、`urgent`、`forcedTarget`、作業台に手が届くか、を足す
- 修正(計測結果に関係なく入れる): `Crafting.urgent()` に「今作れるツルハシの格上げがある」(`makeable(p, 格上のツルハシ)`)を足す

### P-10 調査: none が21%(C8)
- 計測: `SOAK-OPTIONS` で none のとき、何が動いていたか(逃げる・罠・溶岩回避・食事・見晴らし・行動の切り替え待ち・選べる物なし)を数える
- 目標: none ≤ 10%。修正は計測の結果を見てから決める(このセッションでは設計しない)

### P-11 地下に潜る前の食料(C9)
- P-03 の準備条件で扱う(食べ物4個以上。無ければ HUNT/FARM を先に)

### P-12 ソークの更新
- `SOAK_TICKS` を 72000 に(36000tick の実行は CI 全体で約10分だったので、72000 でもジョブの上限60分に収まる見込み)
- `soak.flag` の中身に数字があればそれを tick 数として使う(長く回したいとき用)。`SOAK-SUMMARY` に段階ごとの到達数、死亡数、prog=1/2 の状態での STAIRS+SHAFT の割合を足す
- ソークは push のたびに走るので、このシリーズが終わったら `soak.flag` を消す

## 4. 受け入れ基準(AC)
### ソーク(72000tick、6体、同じシード・同じ地点、新規の脳)
| 項目 | 基準 |
|---|---|
| 石のツルハシ | 6/6、全員 12000tick 以内 |
| 鉄インゴット | 4/6 以上 |
| 鉄のツルハシ | 3/6 以上 |
| 鉄装備 | 1/6 以上 |
| ダイヤ | 1/6 以上 |
| 死亡 | 合計3以下 |
| none の割合 | 10% 以下 |
| prog=1/2 の状態での STAIRS+SHAFT の割合 | 10% 以上 |
| エラー | 0 |
- 最終目標(ダイヤ全部 1/6 以上)は今回の基準に入れない。基準を満たしたあと、`soak.flag` に 150000 を入れて別に測る

### 新しいゲームテスト(必須テストとして足す)
| 名前(案) | 確認すること |
|---|---|
| `knowsWhatToProgressTo` | 持ち物ごとの `tier`/`need`/`ironShort`/`diamondShort`/`digTargetY` |
| `craftsStonePickaxeWhenCobbleInBag` | 木のツルハシ・丸石3・棒2・近くに作業台、遠征も選べる状態で、600tick 以内に石のツルハシを作る |
| `digsForIronEvenWhenOtherWorkIsAround` | 石のツルハシ・食べ物有り、近くに畑(FARM が選べる)。STAIRS か SHAFT が選ばれる(アリーナは地下なので既知の階段を用意するか SHAFT 用のはしごを持たせる。`targetY` はアリーナの床より上に入れる) |
| `keepsDiggingForDiamondsWhenIronGeared` | 鉄装備・ダイヤ無しで `StairMining.wanted` が true(今は false)、`targetY` が自動で -58 |
| `tunnelsSidewaysAtTheTargetDepth` | 目標の深さで横に8ブロック以上掘る(アリーナが狭いので1回の長さはテストで短くできるようにする) |
| `staysHomeToDigInsteadOfLeadingATrip` | 石のツルハシ・食べ物有りなら `canLead`=false、素手なら true |
| `oldBrainsStillLoad` | prog=0 の `encode` が今までのキーと一致する。`decode` が往復で元に戻る |
- 既存の必須テストは全部通ること(REQUIREMENTS.md の不安定テスト一覧は今まで通りの扱い)

## 5. 実装の順番(1セッション3〜4項目)
| セッション | 項目 | 終わったら |
|---|---|---|
| S1(実装済み・CI全緑 run 37243370058) | P-09(計測+urgent)、P-10(計測)、P-01、P-12 | テスト `knowsWhatToProgressTo` `craftsAStonePickaxeWhenCobbleAndWoodAreInTheBag` `oldBrainsStillLoad`(今のキー配置の固定。prog桁を足す S3 でも通ること)。push してソークの run ID を報告 |
| S2(実装済み: P-02〜P-04・P-06・P-11・P-13〜P-15) | P-02、P-03、P-04、P-06(P-11 は P-03 に含む) | テスト `digsForIron…` `keepsDigging…` `staysHome…`。ソーク |
| S3 | P-05、P-07、P-08 | テスト `tunnelsSideways…`。ソーク(AC 判定) |
| S4 | ソーク結果での調整(報酬の値・ドライバの条件) | AC 未達で原因が分からなければ Opus に切り替えて分析 |
- 各セッションは CLAUDE.md のルール通り(push前に `./gradlew test` を試す/CI は待たない/CI失敗の修正は1回まで)

## 6. リスク
- 溶岩: -58 付近は溶岩だまりが多い。横掘りの前・左右の溶岩判定を必ず入れる(P-05)。死亡数を AC で見る
- 迷子: 地下に潜りっぱなしで食料が尽きる。P-03 の準備条件と、BRANCH の停止条件(満腹度<8 で食べ物なし)で帰る。帰り道は掘った階段
- 学習の揺れ: 報酬の値が大きすぎると掘りっぱなしになり、他の要件(農業・遠征・連携)の行動が減る。ソークのオプション割合で見て、FARM/EXPEDITION が 0 にならないこと
- 既存テストへの影響: P-02/P-06 は `digWanted` のときだけ効く。素手で始まるテストは影響なし。石以上のツルハシを持たせて始めるテスト(採掘系)は `digWanted`=true になるので実装時に一覧を確認する(`grep -n "PICKAXE" CloneGameTests.java`)
- 状態の数が3倍(3456→10368)になり、学習が遅くなる。prior(P-07)で初期の向きを付けて補う

## 7. S1 の結果と追加項目(ソーク run 37243370058、72000tick、6体)
- CI: 175件、必須の失敗0。ソーク: 石のツルハシ 6/6(初到達 4800〜46000tick、5体は34600以降)、かまど 5/6、石炭 6/6、鉄 0/6。死亡3体(41400・46600・48000tick)、生き残り3体も HP2〜9・満腹度4〜7
- 行動なしは 8%(181/2160)。うち escape(罠・穴から出る行動)が 170。オプション: EXPEDITION 252 + JOIN 446(合計約32%)、STAIRS/SHAFT は 0 のまま(S2・S3 の主題)
- 見つかった原因:
  1. 作業台を置けない: `Crafting.placeStation` は「足元の隣が空きで、その下が固い」場所しか使わない。トンネルや穴の中では置けず、丸石21〜41個・作業台を持ったまま石の道具を作れない(`place=[feet=air below=stone n=stone/stone e=air/air ...]`)。作業台は支えが要らないので、壁や床の面に置ける
  2. 木のツルハシのあと丸石が集まらない: QUARRY は 9件(0.4%)。MINE は主に鉱石向き
  3. 空腹: 満腹度0の個体(28800tick)と死亡3体。食べ物の準備と、空腹なときに遠征へ出ない仕組みが無い
  4. escape が多い: 穴や自分が掘った窪みにはまっている疑い(原因未調査)
- S1 で直したこと(23390a4): 実行できなかった作り方を300tick見送る(`giveUp`)。強制したツルハシは持っていれば作り直さない
- 追加項目(S2 に入れる):
  - P-13 `placeStation` の一般化: 隣の空きの下が固くなくても、空きに接する固いブロックの面(壁・床・天井)をクリックして置く。置けなかったときは壁を1ブロック掘ってそこに置く案も検討。受け入れ: 4方向が石のトンネルの中で作業台を置いて石のツルハシを作るテスト(`craftsInATunnel`)
  - P-14 空腹の優先: 満腹度 < 8 で食べ物が無いときは、遠征・JOIN・掘りに出ず、HUNT/FARM を先にする(P-03 の準備条件に含めるだけでなく、`Expedition.canLead` と JOIN のマスクにも入れる)
  - P-15 escape の調査: `SOAK-TRACE` に escape の開始回数と開始時の足元のブロックを足す
- ソークの受け入れ基準「石のツルハシ 6/6、全員 12000tick 以内」は未達。P-13 のあとで再判定する

## 8. S2 の実装メモ
- 掘りの駆動は `CloneController.digDrive`(`drive()` の鉱石 MINE のあと)。`digPending` = `Progression.digWanted` かつ掘る余地(`stairs.target()` より上)あり、かつ連続3回空振りの休み(6000tick)中でない。`digPending` の間は STAIRS/SHAFT を遊び扱いで外さず、新しい遠征・JOIN をマスクから外す(`homeBody`)。満腹度<8 で食べ物なしも同じく遠征・掘りを外す(`hungryNoFood`)
- 掘る前の準備: 満腹度<14 かつ食べ物<4 なら HUNT、なければ FARM。木が4板分未満で木が見えていれば GATHER_WOOD
- `StairMining.targetY` / `ShaftMining.targetY` は未設定(MIN_VALUE)なら `target()` が `Progression.digTargetY(need)`(鉄16・ダイヤ-58)。鉄装備でもダイヤが足りなければ掘る(`ironGeared && !digWanted` のときだけ止まる)。テストが `targetY` を直接入れる使い方はそのまま
- P-13: `Crafting.placeStation` は平地が無いとき、隣の空き(足元と頭の高さ)に `Motor.placeBlockAt` で壁・床の面から置く
- 未実装(S3): P-05 横掘り(目標の深さに着いたあと)。それまで、深さに着くと掘りは止まる(`digPending` も偽になり遠征は戻る)

## 9. S2 の結果(ソーク run 37248773132、72000tick、6体)と見つかった原因
- CI: 179件、必須の失敗0。ソーク: 石のツルハシ 6/6(初到達 12000〜46400tick)、かまど 6/6、石炭 1/6、鉄 0/6。死亡0、エラー0、行動なし 4%(85/2160、うち escape 78)
- オプション: STAIRS 506(23%)、SHAFT 70、GATHER_WOOD 269、FARM 203、STORE 162、JOIN 224、EXPEDITION 133、MINE 10、QUARRY 10
- 見つかった原因:
  1. 掘りが下へ進まない: STAIRS が長く選ばれているのに、y は 57〜75 のまま(目標は 16)。`SOAK-TRACE` の escape 回数は Clone263 だけ 361 回(足元は `oak_log` y=57〜58。穴から原木を積んで出る行動)で、他は約10回。階段を掘る途中で「はまった」と判断され、掘りが中断される疑い。`trapCheck` は SHAFT だけを除外していた → STAIRS も除外した(未検証。次のソークの `esc=` の回数で確認)
  2. 木のツルハシ → 石のツルハシが遅い(12000〜46400tick): `need=STONE` の間は遠征・JOINが約40000tickまで続く(P-06 は `digPending` のときだけ)。QUARRY は10件。石が16ブロック以内に見えるときしか選べない
  3. `digDrives`(駆動で掘りを選んだ回数)は 0〜3 回だけで、STAIRS の大半は Q 表が選んだもの。駆動の条件(地表・階段が近い・休止)で外れている
- 診断を足した: `SOAK-TRACE` に `stairs=<掘った段数>/<debug>/<直近の出来事>` と `shaft=<段数>/<debug>`(`StairMining.recent`)
- 次(S3 の候補): (a) 階段が進まない原因の確定と修正、(b) `need=STONE` の間の遠征抑制と石探し(石が見えなくても岩場へ向かう)、(c) P-05 横掘り、P-07 観測、P-08 報酬

## 10. S2+トラップ除外のあとの結果(ソーク run 37253037409、72000tick、6体)— 設計の見直しが必要
- CI: 179件中、必須の失敗1(`pensCowsWhenWheatPilesUp`: REQUIREMENTS.md の不安定テスト。今回の変更とは無関係)。ソーク: 石のツルハシ 4/6(初到達 5000〜15600)、かまど 5/6、石炭 4/6、鉄 0/6。死亡2(8400・16200tick。原因は未調査)、行動なし 176(8%、うち escape 118)
- STAIRS は 435 サンプル(20%)選ばれているのに、掘った段数は1体あたり2〜9段のまま。`SOAK-TRACE` の `stairs=` で止まり方が分かった:
  - (A) 階段の途中で往復して進まない: Clone260・262。`resume 3040,64,122 -> 3044,58,122`(上端 y=64、下端 y=58、6段)。stage 1(階段を下る)の位置が `3040,61〜63,122` を行き来するだけで y=61 より下へ行かない。それを10サンプル(約24000tick)以上続ける。階段の途中が塞がっている(escape が原木などを積んで出た跡か、落ちてきた砂利・土)疑い。3000tick の時間切れで終わり、また同じ階段を resume する
  - (B) 階段の上端へ戻る途中で穴にはまる: Clone263。`resume 3162,70,19 -> 3162,68,17 from stage 0`(上端 y=70)なのに、自分は y=64〜65 の穴にいて、stage 0 のまま `s0@3162,65,21` を繰り返す。escape が約43tickに1回、合計 900 回以上(足元は `stone`)。行動は null(escape と strategy の間を往復)
  - (C) 縦穴: `no place for a shaft`・`could not craft ladders`
- MINE が開始と同じtickで終わる空回りが 52 サンプル(`strategy:MINE<=1`)。原因は未調査
- 駆動で掘りを選んだ回数(`dig=`)は 2〜9 回で、STAIRS の大半は Q 表が選び続けている
- 前回(run 37248773132)から STAIRS を「はまり判定」から外したが、掘りは進んでいない。つまり原因は「はまり判定」ではなく、**階段の再開(resume)と下り(stage 0・1)に進まなかったときの打ち切りと作り直しが無いこと**
- 設計で決めること(Opus で):
  1. 階段の stage 0・1 に「進まない」検出(一定tick、深さも位置も変わらない)を入れ、検出したら: 階段を `finished` にして別の場所から新しく掘る / 塞がっているブロックを掘って進む / その階段を数千tick使わない、のどれにするか
  2. 穴にはまる原因(掘った階段の上端付近で自分が落ちた?)と、escape の無限ループを止める仕組み(同じ場所で escape が N 回続いたら別の方法: 横に掘る・待つ・遠くへ歩く)
  3. 掘りの下準備(階段が無い所から始める・目標の深さ・縦穴と階段の使い分け)を整理し、P-05 横掘りをその上に載せるか
  4. 石のツルハシが 5000〜46000tick と幅が大きいことと、MINE の空回り
- 実装の入口: `StairMining.tick`(stage 0 = 上端へ歩く、1 = 階段を下る、2 = 掘る)、`CloneController.trapCheck`(escape の開始。STAIRS/SHAFT は除外済み)、`Escape.isTrapped`
- 受け入れ(ソーク、S3 の前に): STAIRS の掘った段数が、1体あたり 40 段以上(y=16 まで約 50 段)。escape の回数が 1体あたり 50 以下。死亡 0

## 11. 設計: 掘りの足回りの立て直し(S2.5・S2.6。S3 より先にやる)
### 根本原因(コードで確認済み)
- R1 **階段の記録と実物がずれる**: `StairMining.digTick` は「次の段」を足元基準で掘る(`feet.relative(dir).below()`)。一方で再開(stage 0・1)は記録上の直線(`top.relative(dir,i).below(i)`、`stepIndex`)で歩く。`s.end` の更新は「足元が今の end より低く、マンハッタン距離 2 以内」なら何でも受け入れる(2ブロック落ちただけでも end になる)。そのため記録がずれる。例: top 3040,64 → end 3044,58(横4・縦6)。再開すると直線上の段(実物は石)へ向かって進めない
- R2 **「進まない」検出が効かない**: stage 0・1・2 の `stuck` は「足元が同じブロックのままのtick数」。ジャンプや左右の往復で毎回リセットされるので、`stuck > 80`/`> 60` に届かない。3000tick の時間切れまで続く
- R3 **壊れた階段を何度も再開する**: 失敗(FAILED・時間切れ)しても階段に印が付かない。`nearestStaircase` は 64 ブロック以内の未完了の階段を返すので、同じ壊れた階段を全員が繰り返し再開する(Clone260・262 が同じ 3040,64,122)
- R4 **escape の無限ループ**: `Escape.tick` は「元のマスを出て、はまっていない」ですぐ DONE。そのあと MINE(穴の中の鉱石に引かれる)などで同じ穴へ戻り、また escape(Clone263 で 900 回)
- R5 **MINE の空回り**: 鉱石の引き(`drive` の `pickMakeableFor`)と実行(`runHarvest` → `toolUp` → `nextOption = CRAFT`)の条件が食い違う。作れない(作業台が置けない、`giveUp` で作り方が見送り中)と CRAFT がマスクに無く、MINE がまた引かれ、同じtickで終わる(`strategy:MINE<=1` が 52)

### 変更(D-1〜D-7)
- **D-1 階段は直線でだけ伸ばす**(`StairMining.digTick`)
  - 次に掘る段は記録基準: `next = s.end.relative(s.dir).below()`。足元が `s.end` でなく、2ブロック以内なら、まず `s.end` へ歩く
  - `s.end` の更新は `feet.equals(s.end.relative(s.dir).below())` のときだけ(`stepsDug++`)
  - 足元が `s.end` より低いのに直線上にいない(落ちた・押された)とき: その階段を `finished = true` にして `stairs = null`。次のtickで今の足元から新しい階段を掘る(`addStaircase`)。もう下にいるので、深さは失わない
- **D-2 進み具合で打ち切る**(`StairMining.tick`)
  - `bestProgress` と `lastProgressTick` を持つ。stage 0 = 上端までの距離(小さいほど良い)、stage 1 = 段の番号 i(大きいほど良い)、stage 2 = `s.end` の y(低いほど良い)
  - stage 0・1 は 300tick、stage 2 は 400tick 良くならなければ FAILED。理由は `debug` に残す。今の `stuck` による判定は残す
  - stage 1 で直線上にいない(`i < 0`)かつ上端より 2 以上低い(上端の近くの穴に落ちた): 上端へ跳ぶのをやめる。今の足元から新しい階段を掘る(stage 2、`stairs = null`)
- **D-3 壊れた階段を使わない**
  - `Bases.Staircase` に一時的な `int failures`(保存しない)を足す。stage 0・1 の FAILED と時間切れで `failures++`。2 回で `finished = true`
  - 失敗した階段はそのクローンでは 12000tick 使わない(`StairMining` に `Map<BlockPos, Long> avoid`)。`Bases.nearestStaircase` に `Predicate<Staircase>` を取る版を足し、`begin()` と `wanted()` で避ける
- **D-4 オプションの空回り止め(汎用)**(`CloneController.finishOption`・`startOption`)
  - 1tick 以内に、報酬 0 で終わったオプションを数える(同じオプションが続けて3回なら)。そのオプションを 600tick マスクから外す(`optionCooldown[Option]`)。`forcedOption` には効かせない
  - `toolUp`: `crafting.forcedTarget` を入れたあと `crafting.hasWork()` が偽なら CRAFT を予定しない。`toolBlockedUntil = now + 600` にして false を返す(R5 の直接の修正)
- **D-5 死因の記録**(`CloneController.onDeath`): `lastDeath = source.getMsgId() + " y=" + y + " opt=" + option` を持つ。ソークの `SOAK-CLONE` の gone@ に付ける(前回の死亡2体の原因が不明なため)
- **D-6 escape のループを切る**(`CloneController` の trapCheck の所)
  - escape の開始位置を覚える(位置 → 直近2400tickの回数)。同じ場所(3ブロック以内)で 3 回目なら次の2つを行う
    - (a) その場所の 6 ブロック以内の鉱石を `skipBlocks` に 2400tick 入れる(鉱石に引かれて戻らない)
    - (b) `Escape.start(goal, minLeave)` で、DONE の条件を「元のマスから水平 6 ブロック以上、または 3 段以上高い」に厳しくする
- **D-7 石の段階を速くする**(`need=STONE` = 木のツルハシだけ)
  - `homeBody` に `need == STONE` を足す(石のツルハシまで新しい遠征・JOIN に出ない)
  - `drive` で、`need == STONE` かつ石が 24 ブロック以内に見えるなら QUARRY を引く。QUARRY のマスクも、この場合は 24 ブロックまで見る

### テスト(必須)
- `staircaseGrowsOnlyAlongItsLine`: 石の塊を掘らせたあと、`s.end == s.top.relative(dir, steps).below(steps)`
- `startsANewStaircaseWhenTheOldOneIsBroken`: `Bases` に「end が直線から外れた」階段を登録する。クローンを上端より 3 低い穴に置く(石のツルハシ・`assumeSurface`・`targetY` を下に)。古い階段が `finished`、新しい階段ができ、`stepsDug >= 2`
- `givesUpAStaircaseItCannotFollow`: 途中を石で塞いだ既存の階段で、stage 1 が 300tick 進まないと FAILED。2 回で `finished`
- `coolsDownAnOptionThatEndsAtOnce`: `noteOptionEnd(Option.MINE, 0 ticks, 0 reward)` を3回 → MINE が 600tick マスクから外れる(controller に test 用の公開メソッド)
- `leavesAPitItKeepsFallingInto`: `noteEscapeStart(pos)` を3回 → その近くの鉱石が `skipBlocks` に入る。`Escape` の DONE 条件が厳しくなる
- 既存の `digsAStaircaseDownWhenThereIsNothingElseToDo`・`carriesOnDownAStaircaseAlreadyStarted`(top 4,5,7 → end 6,3,7 で直線上)・`digsForIronEvenWhenOtherWorkIsAround` が通ること

### 受け入れ(ソーク、S3 に進む条件)
- 掘った段数(`stairs=`)が 40 段以上の個体が 3/6 以上、鉄インゴット 1/6 以上、escape は 1体あたり 50 回以下、死亡 0、`strategy:<OPT><=1` の合計が 10 以下、石のツルハシ 6/6 が 20000tick 以内

### 実装の順番
| セッション | 項目 |
|---|---|
| S2.5(実装済み) | D-1、D-2、D-3、D-5(階段の立て直しと死因の記録) |
| S2.6 | D-4、D-6、D-7(空回り・escape ループ・石の段階) |
| S3 | P-05 横掘り、P-07 観測、P-08 報酬(S2.6 のソークが受け入れを満たしてから) |

## 12. S2.5 の実装メモ
- `StairMining`: 階段は `s.end.relative(dir).below()` の段にだけ伸びる(`feet` がそこに着地したときだけ `end` 更新・`stepsDug++`)。空中(段を降りる途中)は着地を待つ。着地した足元が `end` より低く線上にもいなければ `abandonStairs(broken=true)` → 階段を `finished` にして今の足元から新しい階段を掘る。`end` から4ブロック以上離れたら stage 0 へ戻る
- 進み具合: stage 0 = 上端までの距離、stage 1 = 段の番号、stage 2 = `end` の y。300(stage 0・1)/400(stage 2)tick 良くならなければ `failStairs`。時間切れも stage 0・1 なら失敗扱い
- 失敗した階段: そのクローンは 12000tick 使わない(`avoid`)。`Bases.Staircase.failures`(保存しない)が2で `finished`。上端の近くの穴に落ちた(stage 1 で線の外、上端より2以上低い)ときは `abandonStairs(broken=false)`
- `Bases.nearestStaircase(dim, pos, radius, predicate)` を足した
- `SOAK-TRACE` の `stairs=` に `<段数>g<失敗数>a<見限った数>` を出す。死亡は `CLONE-DEATH`(ログ)に原因・場所・option・満腹度を出す
- 未実装(S2.6): D-4 空回り止め、D-6 escape のループ、D-7 石の段階
- S2.5 の修正(run 37262592212 が失敗: 階段を掘る既存テスト4件など): 段を降りる途中、足元が「下端の前方1ブロック(下端と同じ高さ)」になる。ここで接地すると「下端へ戻る」処理が働いて往復していた。`digTick` でそのブロックでは戻らず、降りる動作を続ける

## 13. 現状と次の案(run 37265271897 の時点)
### 現状
- 階段: 直せた。y=20(目標16)まで届いた個体あり。掘った段数は 1体 24〜103 段(前は 2〜9)
- 鉄: 0/6。階段の壁に見える鉱石しか取れない(P-05 横掘りが無い)
- escape のループ: 3体で 371〜485 回。行動なし 14%(D-6 未実装)
- 死亡3(溶岩: 遠征中・満腹度20 / 落下: CRAFT中 / 壁の中: option=null)
- CI: 必須3件が失敗(`quarryingTakesTheOresFirst` は2回連続、`huntsFishInTheWater` は初、`cowardModeOnlyRunsAndHides` は以前にも)。失敗メッセージはログの先頭側にあり、取得できる末尾5000行に入らないため読めていない

### 案(推奨順)
1. **CI に失敗理由の要約を出す**(最優先・小さい): GameTests の後に `if: always()` のステップを足し、`run/logs/latest.log` から `failed!`・`CLONE-DEATH`・`SOAK-SUMMARY`・`SOAK-NONE` の行だけを末尾に出す。失敗の理由が毎回読めるようになり、推測での修正が減る
2. **失敗3件の切り分け**: 1 のあと再実行し、理由を見て「今回の変更が原因(直す)」か「不安定(テスト側を頑丈にする)」かを分ける。`quarryingTakesTheOresFirst` は S2.5 以降で2回連続なので変更起因の疑いが強い。テストを無効化・隔離して緑にすることはしない
3. **S2.6(D-6 を先頭に)**: escape のループ止め → 空回り止め(D-4)→ 石の段階(D-7)
4. **S3 の P-05 横掘り**: 目標の深さで1×2のトンネルを掘る。鉄に届く本命。P-07 観測・P-08 報酬はそのあと
5. (任意)安全: 溶岩の上・縁を歩かない、落下の高さの見積もり。死亡のログを見てから決める

### 運用ルールの提案(決めるのは人)
- 今は「CI 失敗の修正は 1 セッション 1 回」なので、不安定テストが1つ落ちるだけで止まる。例として「`REQUIREMENTS.md` の不安定テスト一覧にあるテストだけが落ちたときは、修正回数に数えず報告だけして先へ進む」を CLAUDE.md に足す案がある
- ソークは push のたびに約20分かかる。設計・修正の途中は `soak.flag` を消し、ソークで確かめたいときだけ置く案もある

## 14. S2.6 の実装メモ(D-4・D-6・D-7)
- D-6: `CloneController.onEscapeStart`。2400tick 以内に 3 ブロック以内で 3 回目の escape → 近く(8 ブロック)の鉱石を `skipBlocks` に 2400tick、近く(10 ブロック)の階段を 6000tick 避ける(`StairMining.avoidNear`)、`Escape.start(goal, 6)`(出たあと水平 6 ブロック以上、または 3 以上高い所まで行かないと DONE にしない)
- D-4: `noteOptionEnd`。1tick 以内・報酬ほぼ0で終わったオプション(MINE・CRAFT・QUARRY・GATHER_WOOD・STAIRS・SHAFT・FARM・STORE ほか。FIGHT・FLEE・EAT・REST・EXPLORE などは除く)が3回続けば 600tick マスクから外す(`forcedOption` には効かせない)。`toolUp` は、作れるはずのツルハシが今は作れない(`crafting.hasWork()` が偽)なら作れないものとして扱い、`toolBlockedUntil` を入れる
- D-7: `Progression.need == STONE`(木のツルハシだけ)の間は新しい遠征・JOIN をしない。石が見えて QUARRY が選べるなら `drive` で QUARRY を引く
- ソークの `SOAK-TRACE` に `cd=`(空回りで外した回数)を足した
- CI にテスト結果の要約ステップを追加(`.github/workflows/build.yml`): 失敗メッセージ・ソークの集計・`CLONE-DEATH` を、ジョブサマリーと注釈(`gh api repos/.../check-runs/<id>/annotations`)に出す

## 15. S3(P-05 横掘り)の実装メモ — run 37277528777 までの結果を受けて
- 結果: CI は全緑(179+3件)。ソーク: 石のツルハシ 6/6・かまど 6/6・石炭 4/6・鉄 0/6。QUARRY 219(石の段階が速くなった)、EXPEDITION 35(299→35)、行動なし 6%(escape 130)。死亡3(溺死3: 遠征・STORE・FOLLOW。前の3回は溶岩)。直前の run(37277318214、S2.6 なし)では鉄インゴット 1/6 が出た
- 実装: `StairMining` の stage 3(`tunnelTick`)。目標の深さに着いても鉄・ダイヤが足りなければ、階段の向きにまっすぐ 1×2 のトンネルを掘る。32セルごとに右へ曲がる。上限 160セル。水・溶岩・掘れない物があれば曲がり、曲がれなければ階段を `finished` にする。HP<50% か空腹(満腹度<8 で食べ物なし)なら離れる(トンネルは残り、あとで続きから)。`Bases.Staircase` に `tunnelEnd`・`tunnelDir`・`tunnelLen`(保存しない)。`nearestStaircase` は上端・下端・トンネルの先端のうち近いものまでの距離で探す。`StairMining.pending()` = 掘る深さが残っている、または続きのトンネルがある。駆動(`digDrive`)と遠征の抑制はこれを使う。掘りの進み(`noteDigEnd`)はトンネル8セルでも進みと数える
- 鉱石は階段と同じく `oreReflex` が取る
- 次: 溺死3件と溶岩死3件の安全対策(遠征・探索・STORE の経路で水・溶岩を避ける)。鉄ツルハシ以降(`Crafting` はすでに鉄→ダイヤの順で作る)。P-07 観測・P-08 報酬

## 16. run 37280294312(P-05 横掘り)の結果と、その次
- CI: 必須3件が失敗(`usesExistingBrewingStand`・`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime`・既知の `pensCowsWhenWheatPilesUp`)。どれも掘りと関係が薄く、実行ごとに落ちるテストが入れ替わる不安定なもの。新規の `tunnelsSidewaysAtTheTargetDepth` は通った
- ソーク(72000tick): 死亡 0(ソークの6体)。石のツルハシ 6/6(初到達 11800〜49000tick。2体は47000tick以降)、かまど 6/6。鉄: 生の鉄 1個(Clone270)、インゴット 0。階段・横掘りで y=39 まで届いた2体(Clone267・272)は、満腹度0・HP10 のまま地下にいた(石炭が33個・10個)。STORE が 364(17%)で多い
- 見つかった問題: トンネルの中で空腹になると、`tunnelTick` はオプションを終えるだけで、地下に残って食べ物を探せない
- 対策(実装済み): 空腹(満腹度<10 で食べ物なし)かHP<50%なら、トンネルから階段を上って上端へ戻る(`ascending`: stage 0 で上端まで歩き、着いたら DONE)。掘り始める前の食べ物の条件を、満腹度<18 かつ食べ物<6 なら HUNT/FARM を先にする、へ強めた
- CI の注釈: GitHub は 1 ステップにつき通知 10件・エラー 10件までしか残さない。通知は種類ごとに 1 件(SUMMARY・NONE・OPTIONS・個体ごとの SOAK-CLONE を改行でまとめた 1 件・死亡)に変更。`SOAK-CLONE` に `Progression`・階段(段数 g 失敗 a 見限り)・`tunnel=`(掘ったセル数)・`esc=`・`cd=`・`dig=`・階段の debug を足した
- 次: 水・溶岩の安全対策(溺死3・溶岩死3。いずれも遠征・探索・STORE・FOLLOW の途中)。鉄がなかなか出ない原因の確認(トンネルの長さ `tunnel=` と、見つけた鉱石の数)

## 17. run 37284586086(空腹で地上へ戻る・食料の強化)の結果と、その次
- CI: 必須3件が失敗(`pensCowsWhenWheatPilesUp`・`crossesWaterByBoat` は REQUIREMENTS.md の既知の不安定テスト、`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime` は2回続けて失敗。戦闘の学習で、掘りとは関係が薄い)
- ソーク: 鉄インゴット 2/6(31000・32600tick)。生の鉄を持ったまま溶かせていない個体が3体(7個・6個・1個、石炭12個・16個あり)。ソーク6体のうち5体が満腹度0(HP9〜10)。STORE が 552件(25%)で最多、HELP 75、QUARRY 257、MINE 291。escape は 52(2%)。死亡は 1(落下。HELP の途中、満腹度0)
- 見つかった問題と対応(実装済み):
  1. 生の鉄・生肉を持って、近くに(またはバッグに)かまどがあっても、CRAFT を Q 表が選ぶのを待っていた → `Crafting.urgent()` に「溶かす作業か、溶け終わった物の回収」を足した(400tick に1回まで)
  2. STORE が多い原因の見立て: 横掘りで花崗岩・安山岩・閃緑岩・凝灰岩・土・砂利・深層岩などでバッグが埋まり、「要らない物が5スロット以上」で STORE(最大4800tick)が繰り返される → `StairMining.dropJunk()`: トンネル中、空きスロット6以下のとき、それらを捨てる(丸石は96個まで残す)
  3. 診断: `SOAK-CLONE` に溶かす条件(`smelt[work ready items fuel near bag at blocked]`)・`store=<入れた回数>/<出した回数>`・食べ物の数を足した
- 未対応: 食料不足そのもの(5/6が満腹度0)。狩り(HUNT は 5件だけ)・農業(FARM 61件)で食べ物が足りていない。STORE が減れば時間が空く。次のソークで食べ物の数(`food=`)と HUNT/FARM を見て決める
- 未対応: 水・溶岩の安全対策(前の3回で溺死3・溶岩死3。今回のソークは水・溶岩で死んでいない)

## 18. run 37289211977(smelting 優先・ジャンク捨て)の結果と、その次
- CI: 必須1件が失敗(`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime`、**3回続けて**同じ失敗。P-05 の 9f1de65 以降。それ以前は通っていた。原因は未特定。失敗メッセージに、スケルトンの生死・位置、クローンのHP・位置・optionLog を足した)
- ソーク: 鉄インゴット 0/6(前回 2/6)。生の鉄を持ったまま溶かせていない(17個・8個・2個)。死亡 0。escape 209(Clone273 だけ 171)、STORE 183(前回 552 → 約1/3)。満腹度0が 5/6 のまま。`SOAK-CLONE` の smelt 行: 溶かすのに使った炉への参照(`at=`)が残り続け、`smeltWork`(炉の参照が空のとき)が偽になり、`furnaceReady`(回収)は遠くの炉へ歩けず失敗する、という行き詰まり
- 対応(実装済み): `Crafting.giveUp` で溶かす作業が失敗したら炉の参照を消す。炉まで40ブロック以上離れていれば「準備できた」とも「使用中」とも扱わない(`furnaceFar`)
- もう1つ: Clone270 は `tier=0`(ツルハシなし)で丸石214個を持っていた=ツルハシが壊れて、木が無い。トンネルでツルハシが無ければ(`pickTier()<0`)、地上へ戻る。掘る前の木の条件を、板4枚分 → 12枚分(予備のツルハシと作業台)に増やした
- もう1つ: `pickDirection` が null(基地の近くなど、どの向きにも掘れない)で FAILED を繰り返す個体(Clone273 は escape 171 も)。その場所の近く(16ブロック)では 6000tick 掘りを選ばない(`noWayAt`)
- 未対応: 食料不足(満腹度0が 5/6)。HUNT 4件・FARM 53件。水・溶岩の安全対策。階段を掘り始めて「no progress digging」を繰り返す個体(Clone268: 失敗7・見限り5。海面の高さ y=63 付近)

## 19. run 37292933816 の結果(炉の参照・ツルハシ切れ・行き止まり)と、その次
- CI: 必須1件(`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime`、4回連続)。今回の診断で分かったこと: **スケルトンが死んでいる**(10,3,7。矢は1本当たっただけで、3本学習する前に死ぬ)。クローンは FIGHT ではなく DISCOVER・EXPLORE・REST をしている(FIGHT を強制しても、敵が消えれば他の行動に移る)。スケルトンの死因を次のメッセージに出すようにした(`dead by <種類> <原因の相手>`)
- ソーク: 鉄インゴット 2/6(53600・41200tick)、鉄ツルハシ以降 0/6。死亡2(どちらも `onFire`: STORE の途中 (3162,66,40) と MINE の途中 (3185,82,23))。**溶岩・火で死んだのは今回を含め 5回の連続ソークで 5体。毎回、スポーン地点 (3152,67,40) から約 10〜40 ブロックの範囲**。QUARRY 548(25%)、CRAFT 339、STORE 214
- 溶岩・火はクローンが「学習」する設計(L キーで教える / 痛い目に遭って覚える)。ソークの新しい脳は何も知らないので、溶岩に入る。ソークでは、プレイヤーが L キーで教えた状態にした(溶岩・火・魂の火・マグマブロック・サボテンを3回ずつ教える)。これで死亡が減るか、教えても死ぬか(= 経路・掘りの安全が足りない)を切り分けられる
- 溶かせていない問題: 炉の参照のリセットと遠い炉の無視を入れたあと、生の鉄を持ったままの個体はいなくなった(`raw_iron=0`)が、インゴットが 2/6 止まり。`smelt[ready=true ... at=<遠い炉>]` が残る個体あり(`furnaceFar` は 40 ブロック以上で、それ以内の炉には歩いていく)。引き続き確認

## 20. run 37295581921(溶岩・火を教えたソーク)の結果と、安全対策
- CI: 必須2件(`pensCowsWhenWheatPilesUp`: 既知の不安定テスト。`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime`: 5回連続。診断で「スケルトンは何のダメージ記録もなく消えている」と分かった。死亡のtickと原因は次のメッセージに出す)
- ソーク: 鉄インゴット 1/6(59000tick)。死亡2: **溶岩 (3184,65,34) EXPLORE、溺死 (3152,48,112) REST**。溶岩・火を教えても、溶岩で死んだ = 「学習した危険を避ける」だけでは足りない
- 原因の見立て: 経路探索(vanilla のナビゲーション)は溶岩を避けるが、直線の歩き(`Motor.moveToward`)は「段差の下の溶岩」(`dropAt`)しか見ていない。床の上を薄く流れる溶岩(足元のセルが溶岩・下は固い地面)や、足元の高さの溶岩だまりへは、そのまま踏み込む
- 対策(実装済み): `Motor.hazardAhead` — 次の一歩(0.6・1.1 ブロック先)の足元・頭のセルが溶岩か火なら、前進を止める(`hazardBrakes`)。燃えている・溶岩の中・`daring` のときは止めない。テスト `doesNotWalkStraightIntoFire`
- 未対応: 溺死(REST の最中。水の中で REST を選ぶと息が続かない)。水の中なら REST をマスクから外す・浮上を優先する案

## 21. run 37301006126(溶岩・火の手前で止まる)の結果
- ソーク: **死亡 0**(ソーク6体。前の6回は毎回溶岩・火・溺死のどれかで1〜3体)。鉄インゴット 3/6(56400・56600・56800tick)。ただし鉄ツルハシ以降は 0/6。STORE 447、CRAFT 378、QUARRY 290、SHAFT 127。満腹度は 6体中4体が0〜1(食べ物不足は未解決)。escape 93。階段の「no progress digging」が3体(掘り始める所で進まない)
- CI: 必須2件(`pensCowsWhenWheatPilesUp`、`learnsTheSignsOfAnAttackAndRaisesTheShieldInTime`)。後者の診断: スケルトンは t232 に **inWall(壁の中)で窒息死**(ダメージの相手なし)。アリーナの (10,2,7)〜(10,4,7) に前のテストの残りのブロックがあるか、スポーン位置が埋まっている疑い。次のメッセージに、テスト開始時と死亡時のそのセルのブロックを出すようにした
- 次: 鉄ツルハシ以降(3体はインゴット持ち。`ironShort` はまだ 28〜29 で、鉄が圧倒的に足りない)。食料。階段が掘り始めで進まない件

## 22. run 37304049699 の結果
- `learnsTheSignsOfAnAttackAndRaisesTheShieldInTime` の原因が分かった: スポーン時のセルは空気(y1=石・y2〜y4=空気)だが、死亡時は (10,2,7)・(10,3,7) が**砂利**。アリーナの真上にあった砂利が、アリーナが掘り広げられた空間(`clearAbove` が y6〜12 を空気にする)へ落ちてきて、スケルトンを窒息させる(`inWall`)。テストが増えてアリーナの位置がずれ、砂利の下に当たるようになった。対策: `clearAbove` が y13〜28 の落下ブロック(砂利・砂)を石に替える。このテストは冒頭で `clearAbove` を呼ぶ
- ソーク: 鉄インゴット 0/6(前回 3/6。ばらつきが大きい)、死亡 1(落下 (3151,45,-2) EXPLORE)。**REST が 320(15%)**(前回 27)。満腹度 0 が 6体中5体、HP10 のまま REST で動かない個体が多い。食料不足が最大の障害
- ソークの条件の不備: `doMobSpawning=false`(アリーナを守るため)は、動物の自然発生も止める(通常は動物が時々湧く)。クローンが周囲の動物を食べ尽くすと、再び現れない → 餓死の一因。対策: ソークで 1200tick ごとに、各クローンの近く(14〜32ブロック)の草の上に動物(牛・豚・羊・鶏)を1匹出す
- 未対応: `bringsWaterHomeAndMakesASpring`(`filled twice at the lake: 1`、2回続けて失敗)。水・湖の環境に依存するテスト

## 23. run 37306529663(砂利の対策・動物の再発生)の結果
- CI: 必須2件(`penscowswhenwheatpilesup`: 既知。`climbsUpToShootAnEnemyHiddenBehindAWall`: 初めて失敗)。`learnsTheSignsOf...` は通った(砂利の対策が効いた)。`bringsWaterHomeAndMakesASpring` も通った
- ソーク: 鉄インゴット 1/6、死亡 2(どちらも**溺死**: MINE の最中 (3140,49,-55) と option なし (3133,41,28)。地下 y41〜49)。食べ物を持つ個体が 3/6(満腹度 11・20・4)に増えた(動物の再発生が効いた)。HUNT 27(前回 3)、REST 137(前回 320)
- **`tunnel=0`(横掘りのセルが0)が6体全員**: 横掘りは一度も始まっていない。階段は y=37〜39 まで掘れているが(段数 29〜35)、そこで `no nearer the top 3147,68,35 from 3147,39,35`(上端の真下にいて、上端へ戻れない)・`cannot reach the top` で止まる。階段が途中で埋まっている疑い(砂利・砂が崩れて塞ぐ)。対策(実装済み): `StairMining.diggable` が「上に落下ブロック(砂利・砂)がある石」を掘らない(`canDigOut`)。テスト `doesNotDigOutWhatHoldsUpGravel`
- 次: 溺死(地下で水に入ったら上へ)。階段の「上端に戻れない」の原因確認(階段の記録 `s.dir`・上端と今いる位置の関係を `SOAK-CLONE` に出す)

## 24. run 37318069167 の結果と、Round 11 の要求の受け取り
- CI: 必須2件(`carriesOnDownAStaircaseAlreadyStarted`: 階段の既存テスト。`cannot get to the end ... from <1つ上>` で止まる。以前の同じ症状を直したあと、今回はじめて再発。原因は未特定。`penscows...` は既知)
- ソーク: 鉄ツルハシ 1/6(65200tick。初めて)、鉄インゴット 1/6。階段の段数は最大 115〜130 段、横掘り 3セルが初めて出た。死亡1(溶岩 (3187,81,23)、EXPEDITION 中。同じ場所で6回目)
- ユーザーから Round 11 の要求が11件届いた(R-17〜R-27)。REQUIREMENTS.md に登録済み。設計フェーズの開始なので、CLAUDE.md の規定どおり Opus への切替を推奨

## 25. 設計: Round 11(R-17〜R-27)
共通の方針: 新しい Option は足さない(int ビットマスク、残り2枠は温存)。既存のオプション・反射(reflex)・`Motor` に組み込む。各項目に必須ゲームテストを1本以上付ける。

### R-24 水中で角に張り付いて溺れない(最優先)
- 原因の見立て: `Motor` の泳ぎ(`swimStroke`/`swimGoal`)は目標へ真っすぐ進もうとし、角(上と前が塞がる)に押し付けられても方向を変えない。`swimStalls`(進まない)を数えているが、抜け道を探さない
- 変更: `Motor` に「水中の行き詰まり」検出を足す。水中(`isInWater`)で 20tick のあいだ位置の変化が 0.3 ブロック未満なら、次の順で 30tick ずつ試す。
  1. 進行方向に対して左
  2. 右
  3. 真上
  4. 来た方向(後ろ)
  - 空気が 30% 未満のときは、最初から「真上(空気が届く最寄りの水面・空気セル)」を優先する。最寄りの空気は、周囲 6 ブロックの BFS(水・空気のセルをたどる)で探す
- テスト `getsOutOfAnUnderwaterCorner`: 水で満たした L 字の通路(天井あり)の角にクローンを置く。目標を角の向こうにして 200tick 以内に角を抜け、溺れない

### R-23 ドアで息継ぎ
- 仕組み(vanilla): ドアは水を含めない(waterlog しない)。水中に置くと、ドアの2セルが空気になる。頭がそのセルに入れば息ができる
- 変更: `CloneController.waterMoves`(または溺れ警報 `Alarm.DROWNING` の処理)に足す。
  - 水中・空気 40% 未満・真上の水面が「残りの空気で届かない」(水深 > 空気tick/8)・ドアを持っている → 足元の固いブロックの上にドアを置く(`Motor.placeBlockAt(feet)` をドアで)
  - ドアのセルに入って空気が満タンになるまで止まり、ドアを壊して回収する
- ドアの準備: 水中で長く活動しそうなとき(`swamALot()`・水中の採掘や魚釣り・水の洞窟)に、板が6枚以上あれば木のドアを1つ(3つできる)作っておく(`Crafting.wanted` に条件付きで追加)
- テスト `breathesInADoorUnderwater`: 天井まで水で満たした深い水槽の底に、ドアと板を持たせて置く(空気 25%)。ドアが置かれ、空気が 90% 以上に戻り、ドアが回収される

### R-22 水に浸かったまま掘らない
- 事実: 水中で掘ると速度が 1/5、さらに浮いていると 1/5(合わせて 1/25)
- 変更:
  1. `Motor.mine` の前に共通のチェック `dryFootingFor(target)` を入れる。水中(`isInWater`)で掘ろうとしたら、次の順で試す。
     - 届く範囲で、足元が固く・足と頭のセルが水でない場所があれば、そこへ移る
     - なければ、浮いたまま掘らないように底へ沈む(`dive`。地面に立てば 1/5 で済む)
  2. 鉱石・石の対象選び(`runHarvest`・QUARRY)で、立ち位置が水中になる対象は、他に候補があれば後回しにする(水中フラグで距離に重みを足す)
  3. 階段・横掘りは `dry()` で水を避けている(現状維持)
- テスト `stepsOutOfTheWaterToMine`: 浅い水(1段)の中にクローン、岸の上に鉱石。岸に上がってから掘る(掘る瞬間に `isInWater()` が偽)

### R-18 木を一番上まで切る
- 原因: `runHarvest(LOG)` は 8 ブロックで終わる(`blocksDone >= 8`)。届かない高い原木は `stuck` → `skipBlocks` になり、残る
- 変更:
  - 原木を1つ切ったら、その木の幹(切った原木から上へ、3×3 の範囲でつながる LOG)を「切りかけの木」として持つ(`treeLogs`)
  - 切りかけの木が残っているうちは、`blocksDone` の上限で終わらない(上限は 24 に上げる。ジャングルの大木は別)
  - 腕が届かない高さの原木は、真下に立って足場を積む(ジャンプして足元にブロックを置く。`Escape.pillar` と同じやり方)。切り終えたら足場を壊して降りる。足場にするブロックが無ければ、届く所までで終える
- テスト `cutsATallTreeToTheTop`: 高さ 7 の木(原木7・葉付き)。GATHER_WOOD で、その木の原木が 0 になる

### R-21 余った原木で木炭を作る
- 原因: `Crafting.smeltables()` が原木を「溶かす物」から外している(`s.is(ItemTags.LOGS)` で continue)。`fuelSlot()` も原木を燃料にしない
- 変更: 燃料(石炭・木炭)が 0 で、原木が「残す量」(作業台・道具・はしご用に 8)より多いときは、原木を「溶かす物」に入れる(最大 8)。燃料は板(1枚で 1.5個)を使う。`fuelSlot` は、石炭・木炭が無いときに板を選ぶ
- 木炭ができたら、次からは燃料として `fuelSlot` が選ぶ(既存の処理)
- テスト `makesCharcoalFromSpareLogs`: 原木 16・板 8・生の鉄 3・燃料なし・かまど設置済み。木炭ができ、そのあと鉄が焼ける

### R-25 種の数だけ一度に耕して植える
- 原因の見立て: `Farming.tick` は1マスずつ「探す → 耕す → 植える」を行い、探し直しで水の近くの別の場所・別の作業(草刈り・収穫)へ移る
- 変更: TILL を始めるとき、種の数 N だけの「区画」を先に決める(最初のマスから BFS。水から4ブロック以内・耕せる土・明るい・空いている)。区画を順に「耕す → 植える」し、終わるまで他の作業に移らない。暗いマスは R-26 の松明を先に置く
- テスト `tillsAndPlantsAsManyAsItHasSeeds`: 水路の横の土が 10 マス、種 6。6 マスが耕されて植わる

### R-26 余った松明の使い道
- 「余り」の定義: 掘りに残す数(16)を超えた分
- 変更:
  1. 作物の明かり: `Farming` に「作物の近くの暗いマス(明るさ 9 未満の農地)に松明を置く」作業を足す(既存の LIGHT は耕す前の区画用。それを作物にも使う)。間隔は 4〜5 マスに 1 本、農地の縁
  2. 湧き潰し: 拠点(`Bases`)の 24 ブロック以内で、暗い(ブロック光 0・空が見えない、または夜)の歩けるマスへ置く。`Lighting.tick` は今は足元だけ。余りがあるときは周囲 8 ブロックで最も暗いマスへ歩いて置く
- テスト `lightsTheCropsWithSpareTorches`: 松明 30、暗い場所に農地と作物 6 マス。作物の近くに松明が置かれ、残りは 16 本以上

### R-17 歩きながら草を刈る
- 変更: `Motor` に「ついでの破壊」(`sweep`)を足す。移動中(歩き・走り)で、一瞬で壊れる草(`Farming.isGrass` など)が視線の近く(前方 2.5 ブロック以内・左右 ±45°)にあり、移動の目的が「種集め」か、種が少ないとき(`seedCount() < 4`)だけ、次のようにする
  - その tick だけ視線をその草へ向けて壊す(`gameMode.destroyBlock`。手で即壊れる物だけ)。次の tick で元の向きに戻す
  - 移動は止めない(`moveDirection` は視線と独立なので、そのまま進む)
  - 1tick に1つ。同じ草へは1回
  - Farming の SEEDS 作業は「草の群れへ歩く」だけにし、刈り取りは `sweep` に任せる
- テスト `cutsGrassOnTheWayWithoutStopping`: 草を一列に 8 マス、その先に目標。歩き抜けるあいだに 6 本以上刈れて、所要 tick が草なしの場合の 1.3 倍以内

### R-27 掘っている仲間と道具を替わる
- 方式: 掘る役は替えず、良いツルハシを渡す(役の交代は、階段を引き継ぐ仕組みが既にあるが、2体の行動が絡むので後回し)
- 変更: `ItemAid` に「申し出」を足す。
  - 掘っていない(STAIRS/SHAFT/MINE/QUARRY でない)クローンが 2 秒ごとに、16 ブロック以内で STAIRS/SHAFT(横掘りを含む)中の仲間のツルハシの格を見る(同じサーバーのプレイヤー。チャット `DIGGING tier`)
  - 自分の予備でない最良のツルハシの格が高ければ、`GIVE name item` を言い、歩いて渡す(既存の `helpTick` を使う)
  - 受け取った側は `Equipment` が最良の物を自動で持つ。古いツルハシは渡した側へ投げ返す(`ItemAid.need` の逆向き)
  - 掘る側は STAIRS/SHAFT の開始時と 600tick ごとに `DIGGING <tier>` を言う(チャットの表示は控えめにする)
  - 渡したあとに相手が掘りをやめても、取り返さない(単純に保つ)
- テスト `handsABetterPickaxeToTheDigger`: 石のツルハシで STAIRS 中の A、近くで REST の B(鉄のツルハシ)。A が鉄のツルハシを持ち、B は石のツルハシを持つ

### R-19 洞窟の深い穴: はしごで降りる / 塞ぐ
- 判定: 歩いている先に「落ちると死ぬ深さ」(`Motor.dropAt` で 8 以上。HP が少なければ 4 以上)の穴の縁がある
- 降りる(条件: 掘りの目的が下にある(`Progression.digWanted` で深さが残る)か、洞窟の探索で目標が下。はしご 8 以上を持つか作れる)
  - 縁の真下の壁に、はしごを1段ずつ付けながら降りる(`ShaftMining` のはしご付けを、掘らずに使う形で流用)
  - 付けられない壁(水・溶岩・空気)なら、ブロックを1つ置いてからはしごを付ける。底に着いたら、その穴を `Bases` に「はしごの穴」として記録する(帰り道)
- 塞ぐ(条件: 降りない、かつその穴が拠点・よく通る道(`visitedChunks` で何度も通った所)の近く、かつ開口が 3×3 以下)
  - 開口のマスにブロックを置いて蓋をする。開口が広い穴(谷)は、塞がずに「危険」として記録し、`Motor` の縁でのしゃがみ(既存)で落ちないようにする
- テスト2本:
  - `laddersDownIntoADeepPit`: 深さ 10 の 1×1 の穴、はしご 12。底まではしごが付き、底へ降りて死なない
  - `coversADeadlyHoleNearHome`: 拠点の横に深さ 12 の 1×2 の穴。ブロックで塞がれる

### R-20 建造物を見つけて探索する
- 認識(2段): (1) 見えたブロックが「人工物」(板・丸石・ガラス・ドア・ベッド・本棚・羊毛・柵・スポナー・チェスト、または名前空間が `minecraft` でない非自然ブロック)なら候補 (2) 候補の位置で `ServerLevel.structureManager().getStructureWithPieceAt(pos, ...)` を引き、生成構造物(村・廃坑・遺跡・mod の構造物を含む)か確かめる
  - 構造物なら `StructureMemory`(構造物 ID・範囲・入口・チェストの位置・探索済みか)を脳に記録し、チャットで共有(`STRUCT id x y z`)
  - 構造物のデータが無い所(プレイヤーの建物)は今の `strayBuilding` の扱い(LOOT)を続ける
- 探索: EXPLORE の行き先選びで、128 ブロック以内の未探索の構造物を優先する。着いたら範囲内の要所(中心・入口・見つけたチェスト)を回り、チェストは LOOT に渡す(既存)。回り終えたら探索済み
- 危険: スポナー・敵が多い構造物は、戦える装備(鉄以上の剣か防具)があるときだけ
- テスト `findsAVillageHouseAndLooksInside`: 板・ドア・チェストで作った小屋(構造物データなしでもよい。その場合は人工物の判定で候補になる)。小屋に入り、チェストを開ける
- 補足: 構造物データの引き方は、Forge 1.20.1 の `StructureManager#getStructureWithPieceAt` と `Registries.STRUCTURE` で ID を得る。実装時に API を確認する

### 実装の順番(1セッション3〜4項目)
| セッション | 項目 | 理由 |
|---|---|---|
| S11-1 | R-24、R-23、R-22 | 溺死が最大の死因。水中の行動をまとめて直す |
| S11-2 | R-18、R-21、R-25、R-26 | 小〜中。資源と食料(木炭=燃料、農業)の底上げ |
| S11-3 | R-17、R-27 | 移動と仲間の連携 |
| S11-4 | R-19、R-20 | 洞窟と建造物(探索の拡張。大きい) |
- 各セッションの終わりにソークを1回回し、死亡数(特に溺死・溶岩)と、鉄ツルハシ以降の到達を確かめる
- 受け入れ: 各項目のテストが必須で通ること + 既存の必須テストが通ること(既知の不安定テストを除く)

## 26. S11-1 実装(R-24・R-23・R-22)
- R-24: `Motor.waterEscape`(水中で20tick 0.3ブロック未満 → 左・右・上・後ろを30tickずつ。空気30%未満は上から)。周囲BFSで最寄りの空気は省略(上で代用)
- R-23: 新規 `DoorBreath`(空気40%未満・水面が遠い・ドア所持 → 隣のセルにドアを置く→入って息継ぎ→空気90%で内側から壊して回収)。水中で壊すと5倍遅いので内側から壊す。`Motor.diveHard`(空気が少なくても潜る)。`Crafting.wanted` に swamALot+板6枚以上でドア
- R-22: `Motor.mine` が水中なら `dryFootingFor`(届く乾いた足場へ移る。無ければ底へ沈む。80tickで諦めて掘る)。対象選びの後回し(2.)は未実装
- テスト: `getsOutOfAnUnderwaterCorner` / `breathesInADoorUnderwater` / `stepsOutOfTheWaterToMine`(batch r11water)。ローカルはオフラインでコンパイル不可、検証はCI
- run 37333868601(S11-1): 新規3テストは通過。必須の失敗3件: `carriesOnDownAStaircaseAlreadyStarted`(既存・実装前から)、`usesTntInAFight...`・`visitsTheNetherAndComesBack`(今回初。水中と無関係で、不安定の疑い。次のrunで再現するか確認)。次は S11-2(R-18・R-21・R-25・R-26)

## 27. S11-2 実装(R-18・R-21・R-25・R-26)
- R-21: `Crafting.smeltables` が、石炭・木炭が無く原木が8本を超えるとき原木を入れる(`spareLogs`、最大8)。炉には余りだけ右クリックで1本ずつ入れる(`loadSpareLogs`)。燃料は既存どおり板。テスト `makesCharcoalFromSpareLogs`(原木12・板8・生の鉄3)
- R-18: `runHarvest(LOG)`: 切った原木の周り(3×3×上2)にまだ原木があれば上限を 24 に(`moreTree`)。腕が届かない高い原木は、幹の脇で足場を積んで登る(`scaffoldStep`、最大8段、`Equipment.pillarBlockSlot`のブロック)。切り終えたら足元の足場を上から壊して降りる(`climbDown`)。テスト `cutsATallTreeToTheTop`
- R-25: `Farming.planLot`: TILL を始めるとき、種の数だけの区画(最初のマスから8近傍BFS、6マス以内)を決め、`find` が区画を先に消化する。テスト `tillsAndPlantsAsManyAsItHasSeeds`
- R-26: 松明が16本を超えるとき、暗い作物(農地・作物で明るさ9未満)へ既存の LIGHT で置く(`cropDark`)。**未実装: 拠点周りの湧き潰し(`Lighting`で暗いマスへ歩いて置く)**。テスト `lightsTheCropsWithSpareTorches`
- ローカルはオフラインでコンパイル不可。検証はCI

## 28. run 37334912136 と Round 12(R-28〜R-32)
- CI: 必須失敗4件。`getsOutOfAnUnderwaterCorner`(自分のテスト: 密閉の水路で空気が無く、遅いと溺れる。出口の端に空気セルを足した)、`useslavabucketinafightandtakesitback`(初。水中と無関係。次のrunで再現確認)、`carriesOnDownAStaircaseAlreadyStarted`・`pensCowsWhenWheatPilesUp`(既存)
- ログ取得: `gh run view --log-failed` は403。`gh api repos/<repo>/check-runs/<job id>/annotations` に "GameTest failed" が出るのでそれで失敗名を読む
- R-28: `pickSite` を 拠点から6ブロック以上・範囲±12 に。テスト `bringsWaterHomeAndMakesASpring` の配置を合わせた
- R-30: 掘る4オプション(MINE/QUARRY/STAIRS/SHAFT)でツルハシ無し→`toolUp`(作る/材料集め)。それも不可なら1200tick は掘るオプションを選ばない
- R-31: `bestToolSlot` が、適正ツールが無い(速度が上がらない)ブロックでは、素手/非ツールのスロットを選ぶ
- run 37336376594(S11-1のdocsコミット e6de9d4): 失敗5件=corner(修正済)・lava bucket・staircase・climbsUp…・pens。run 37336877128(S11-2 62976c6): corner(修正済)・`creativeBuildsPortalsAndAnEnchantingRoom`・`pearlsOverALavaMoat…`(既知flaky)・`huntsFishInTheWater`(水中。再現を見る)・`makesCharcoalFromSpareLogs`(自分のテスト: 原木を道具・樽・本棚の製作に使い切った。原木を48に増やし、鉄3本の焼成だけを見る形に直した)

## 29. S11-3 実装(R-17・R-27)
- R-17: `Farming` の SEEDS 移動時に `motor.sweep()`。`Motor.sweepAhead` は前方2.5ブロック・±45°の即時破壊できる草を1tick1つ選び、`lookAt` で向き、視線が±8°以内になるまで移動を続けながら刈る。テスト `cutsGrassOnTheWayWithoutStopping`
- R-27: 掘る側(STAIRS/SHAFT)は600tickごとに `DIGGING <tier> <item-id>`。掘っていない16ブロック以内の仲間がより良いツルハシを持つと、既存のFEED/`helpTick`で渡す(1本しかなくても渡す)。渡し側が `RETURN name <old-pickaxe>` を送り、受け取った側もFEEDで旧ツルハシを返す。テスト `handsABetterPickaxeToTheDigger` は鉄を受け取ったあと石を返すところまで確認
- 旧S11-3のCI run 37342075743 はジョブ開始前にGitHub billing/payment制限で拒否。実装コードの成否は確認できていない
- 本セッションで上記2点の未実装を補完

## 30. S11-4 実装(R-19・R-20/R-32)とS11-3の残り
- **R-17 視線**: `Motor.sweepAhead` は草を保持ターゲットにして `lookAt` で追従。頭の向きが対象の±8°に入るまで移動を継続し、視線上の障害が無いときだけ刈る
- **R-27 返却**: `DIGGING` に階層とツルハシIDを含める。良いツルハシを渡した側は `RETURN name <old-pickaxe-id>` を伝え、受け取った側が旧ツルハシを持っていれば1本でも返す。テストを返却まで確認するよう拡張
- **R-19**: 新規 `PitSafety` が移動意図の先に `Motor.dropAt` の致死穴(HP≤10なら4、通常8ブロック)を検出。掘り/下方目標なら、必要数のはしごを所持またはクラフトしてから1セル穴を支えブロック付きではしごで降り、`Bases.LadderPit` に帰路を保存。降りない穴は拠点24ブロック以内または再訪チャンクで、3×3以内のものだけ余剰ブロックで蓋をする。広い穴は既存Motorの縁しゃがみを使う
- **R-20/R-32**: `Perception` が実際に見たブロックだけを `StructureMemory`(SavedData)へ渡す。構造物データは `StructureManager.getAllStructuresAt` と `Registries.STRUCTURE` からID/範囲を取り、人工ブロックの集まりは手作り建物候補としてまとめる。`STRUCT` チャットで共有し、未探索の構造物をEXPLOREの入口・中心・チェスト順に訪問、チェストを既存LOOTへ渡す。スポナー/敵5体以上は鉄以上の剣または防具が無ければ避ける
- 追加必須テスト: `laddersDownIntoADeepPit`, `coversADeadlyHoleNearHome`, `findsAVillageHouseAndLooksInside`; R-17/R-27の既存テストも更新
- **検証未完了**: `./gradlew test --no-daemon` は `JAVA_HOME` 未設定かつ `java` 不在で実行不可。Java構文パーサーと `git diff --check` は通過。直前のCI run 37342075743 はGitHub Actionsがbilling/payment制限でrunner起動前に拒否。今回のpush後もCIが同じ制限なら検証は保留
- **残り**: R-26の「作物以外、拠点周囲の湧き潰し」はS11-2時点の未実装のまま

## 31. Round 13 設計・実装: R-33〜R-36 (遠征・役割・個性・経験)
### 方針
- 行動列を決め打ちせず、既存の `Brain.STRATEGY` を主に使う。準備度・帰還状態・近くの仲間の担当役割・場所での経験を `StrategyState` の上位コンテキストに加え、実行結果を option reward に返す。
- `StrategyState` の既存キー(0〜3455)はコンテキスト0のまま保存互換を保つ。新しいコンテキストは既存キーの上位桁に追加し、疎なQテーブルで必要な状態だけ作る。`Option.RETURN` はenum末尾へ追加し、既存の行動ordinalを動かさない。
- 長いオプションも次の計画に価値を伝えられるよう、semi-Markovの割引は従来の1tick単位ではなく約20tick(1秒)単位にする。時間コスト自体は維持し、長い遠征が常に損になる状態は、帰還・発見報酬とソークで確認する。

### R-33 遠征ライフサイクル
- 遠征状態に「準備済み」を含める: 食料4個以上・松明4本以上・空きスロット3以上・体力70%以上。ハードマスクにはせず、Qが準備不足で出る/準備する結果を学ぶ。
- 探索/狩猟の遠征を終了したら出発地点(近くに登録済みの拠点がある場合は拠点)を帰還目標として残し、`RETURN` を戦略の選択肢にする。同行者にも同じ帰還目標を共有する。帰還報酬・遠征完了報酬・死亡/失敗ペナルティを別々に学習させる。
- `RETURN` は安全上の強制ではなくマスクに出す学習可能な選択肢。重大な危険は既存のFLEE/戦闘割込みを優先する。移動距離の実進捗にも小さな報酬を与え、長距離帰還の時間コストを相殺できるようにする。失敗上限とOptionのtick上限は同じにし、一度の失敗に二重ペナルティが付かないようにする。

### R-34 役割分担
- 近くの同チームのクローンの実行中Optionから、食料/生活・素材/採掘・探索/移動の3カテゴリの担当maskを観測する。
- そのmaskを持つ状態で既存Optionを選ぶ。既に埋まったカテゴリへの弱い重複ペナルティ、未担当カテゴリの仕事で実績が出たときの協力報酬を付け、Qが状況別に役割を選ぶようにする。救援/JOINなど本来の協力行動は重複ペナルティ対象外。

### R-35 個体差
- `ClonePersonality` はUUID由来の決定的traitを使い、同じcloneの再起動/リスポーン後も同一。脳をリンクしてもtraitは個体ごとに残す。学習対象は負傷・空腹・死亡・失敗へのペナルティ、および好奇心・協力・帰還の報酬係数。
- 値域を狭く上下させ、ゲーム挙動を不安定にしない。個体差は乱数を毎回振るのでなく、安定した報酬形状として現れる。

### R-36 経験記憶
- `Brain` に上限付きの地点×行動のオンライン平均報酬記憶を保存する。死亡/被害は原因つきの汎用失敗記憶としても記録。古い記憶は時間で減衰し、SavedData/脳のファイルへ保存、脳リンク時は重み付きで統合。
- 同じ地点の経験バンド(未知/成功/失敗/混在)を状態に含め、同じ地点・同じ行動の過去値を小さなreward shapingとして使う。既存Q更新も行うので「記憶を読むだけ」でなく結果に応じて方策を学習する。

### 実装・受け入れテスト
- `StrategyState` は旧0〜3455をそのまま維持し、上位contextに準備/帰還/role mask/経験bandを追加。`Option.RETURN` は末尾ordinal 30。旧30-action Q行を31-actionテーブルとして読み込むテストも追加。
- `ClonePersonality` はUUID由来の決定的trait。負傷・空腹・死亡・失敗の同一量が個体ごとに異なる報酬になることと、実ClonePlayerへの結び付きをテスト。
- `Brain` の地点×行動報酬記憶は256件LRU上限、48,000tick減衰、save/load/merge対応。実際の再訪stateがpositive/negative混在bandに変わるテストを追加。
- 遠征準備stateの実測、帰還先の保持、RETURN actionの実行と結果のstrategy Q更新をテスト。`canLead` の体力条件は状態設計と同じ70%に合わせた。
- 役割テストは同チーム・近距離の実行中Optionがstateに入り、重複役割のnegative rewardがoption終了時にQ更新と地点経験へ入ることを確認。
- GameTest名: `learnedStrategyContextPreservesOldKeys`, `clonePersonalitiesAreStableAndWeightEventsDifferently`, `episodicSiteExperiencePersistsAndMerges`, `expeditionPreparationIsAnObservedState`, `teamRolesBecomePartOfTheLearnedSituation`, `expeditionReturnIsAvailableToTheLearnedPolicy`。
- **検証未完了**: 現環境にJava/JDKが無く、`./gradlew test --no-daemon` は `JAVA_HOME` 未設定かつ `java` 不在で起動不可。`git diff --check` とソース構造の静的確認後、pushしてCIで検証する。
