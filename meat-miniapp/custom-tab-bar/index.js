// custom-tab-bar/index.js - Q萌卡通底部导航
Component({
  data: {
    selected: 0,
    // 页面内弹出全屏浮层（如切换门店）时隐藏导航栏，避免被遮挡
    hidden: false,
    list: [
      { pagePath: '/pages/index/index', text: '鲜肉选购', icon: '🥩' },
      { pagePath: '/pages/orders/orders', text: '订单', icon: '📋' },
      { pagePath: '/pages/mine/mine', text: '我的', icon: '🐷' }
    ]
  },

  methods: {
    switchTab(e) {
      const index = Number(e.currentTarget.dataset.index);
      const item = this.data.list[index];
      if (!item || index === this.data.selected) return;
      this.setData({ selected: index });
      wx.switchTab({ url: item.pagePath });
    }
  }
});
