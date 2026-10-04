## CI待ちの運用(必須)
- push後はCI完了を待たない。run IDとURLを報告して終了する
- 待つ場合もポーリング禁止。gh run watch <id> --exit-status を1回だけ実行する
- CI失敗の修正は1セッションにつき1回まで。2回目は報告して停止する
- 失敗ログは gh run view <id> --log-failed | tail -100 で絞ってから読む
- flakyテストは診断を足さず隔離してIssueに記録する
- push前にローカルで ./gradlew test を実行してからpushする
