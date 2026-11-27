(() => {
  const invoke = window.__TAURI__.core.invoke;
  const wallet = window.__wallet = {
    active: true, day: 20496, balance: window.__FOCUS_PRODUCT_SAMPLE__ === "zero" ? 0 : 65, time_off_bought: 0,
    time_off_limit: 15, time_off_price_per_minute: 3, needs_upgrade: [],
  };
  const receipts = new Set();
  window.__streak = null;
  window.__TAURI__.core.invoke = async (command, args) => {
    if (command === 'wallet_view') return structuredClone(wallet);
    if (command === 'streak_view') {
      const streak = window.__streak;
      return streak && {...streak, balanceCoins: wallet.balance,
        canBuy: !streak.freezeReady && wallet.balance >= streak.priceCoins};
    }
    if (command === 'streak_buy_freeze') {
      window.__calls.push({c: command, args});
      if (receipts.has(args.requestId)) return {status: 'already_bought'};
      const streak = window.__streak;
      if (!streak) return {status: 'unavailable'};
      if (streak.freezeReady) return {status: 'slot_full'};
      if (wallet.balance < streak.priceCoins) return {status: 'insufficient_coins'};
      wallet.balance -= streak.priceCoins;
      streak.freezeReady = true;
      receipts.add(args.requestId);
      if (window.__loseReceipt) throw Error('Response lost');
      return {status: 'bought'};
    }
    if (command === 'habits_view') return {
      rows: [{habit: {id: 'read', name: 'Read 10 pages', reward: 5, weekdays: 0, interval: 1,
                      time: null}, due: true, streak: 3, week: []}],
      credit: wallet.time_off_bought, cap: 45, wallet_active: wallet.active,
    };
    if (command !== 'wallet_buy_time') return invoke(command, args);
    window.__calls.push({c: command, args});
    if (receipts.has(args.requestId)) return {status: 'already_bought'};
    if (args.expectedDay !== wallet.day) throw Error('Day changed');
    wallet.balance -= args.minutes * wallet.time_off_price_per_minute;
    wallet.time_off_bought += args.minutes;
    receipts.add(args.requestId);
    window.__handlers['habits-updated']?.({});
    window.__handlers['wallet-updated']?.({});
    if (window.__loseReceipt) throw Error('Response lost');
    return {status: 'bought'};
  };
})();
