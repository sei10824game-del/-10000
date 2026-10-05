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
| S2 | P-02、P-03、P-04、P-06(P-11 は P-03 に含む) | テスト `digsForIron…` `keepsDigging…` `staysHome…`。ソーク |
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
