// pages/merchant/shop-setting/index.js
const request = require('../../../utils/request');
const auth = require('../../../utils/auth');

Page({
  data: {
    shopId: '',
    shop: {
      id: null,
      name: '',
      notice: '',
      phone: '',
      businessHours: '06:00 - 19:30',
      address: '',
      longitude: '',
      latitude: '',
      status: 1
    },
    // 同城多阶梯配送规则列表
    deliveryRules: [
      { minDistance: 0, maxDistance: 3.0, minOrderAmount: 20.0, deliveryFee: 3.0 }
    ],
    startTime: '06:00',
    endTime: '19:30',
    submitting: false
  },

  onLoad(options) {
    if (!auth.isAdmin()) {
      wx.showToast({ title: '无掌柜权限', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }
    const shopId = (options && options.shopId) || wx.getStorageSync('adminShopId') || '';
    if (!shopId) {
      wx.showToast({ title: '当前账号未关联门店', icon: 'none' });
      setTimeout(() => {
        wx.switchTab({ url: '/pages/mine/mine' });
      }, 1000);
      return;
    }
    this.setData({ shopId });
    this.loadShopInfo(shopId);
  },

  loadShopInfo(shopId) {
    wx.showLoading({ title: '加载中...' });
    const params = shopId ? { shopId } : {};
    request.get('/shop/info', params)
      .then(res => {
        if (res) {
          const hours = this.parseBusinessHours(res.businessHours);
          let rules = [];
          if (Array.isArray(res.deliveryRules) && res.deliveryRules.length > 0) {
            rules = res.deliveryRules.map((r, idx) => {
              const isUnlim = r.maxDistance === null || r.maxDistance === undefined || Number(r.maxDistance) <= 0;
              return {
                id: r.id || null,
                minDistance: r.minDistance !== undefined ? r.minDistance : 0,
                maxDistance: isUnlim ? '' : r.maxDistance,
                isUnlimited: isUnlim,
                minOrderAmount: r.minOrderAmount !== undefined ? r.minOrderAmount : 20.0,
                deliveryFee: r.deliveryFee !== undefined ? r.deliveryFee : 3.0
              };
            });
          } else {
            // 默认初始阶梯规则
            rules = [{
              minDistance: 0,
              maxDistance: 3.0,
              isUnlimited: false,
              minOrderAmount: 20.0,
              deliveryFee: 3.0
            }];
          }

          this.setData({
            shop: res,
            shopId: res.id,
            deliveryRules: rules,
            startTime: hours.startTime,
            endTime: hours.endTime
          });
        }
      })
      .catch(err => {
        wx.showToast({ title: err.message || '加载店铺信息失败', icon: 'none' });
      })
      .finally(() => {
        wx.hideLoading();
      });
  },

  // 解析营业时间字符串，如 "06:00 - 19:30"
  parseBusinessHours(hoursStr) {
    let startTime = '06:00';
    let endTime = '19:30';
    if (hoursStr && typeof hoursStr === 'string') {
      const parts = hoursStr.split(/[-~至]/).map(s => s.trim());
      if (parts.length >= 2 && parts[0] && parts[1]) {
        startTime = parts[0];
        endTime = parts[1];
      }
    }
    return { startTime, endTime };
  },

  onStartTimeChange(e) {
    const startTime = e.detail.value;
    const businessHours = `${startTime} - ${this.data.endTime || '19:30'}`;
    this.setData({
      startTime,
      'shop.businessHours': businessHours
    });
  },

  onEndTimeChange(e) {
    const endTime = e.detail.value;
    const businessHours = `${this.data.startTime || '06:00'} - ${endTime}`;
    this.setData({
      endTime,
      'shop.businessHours': businessHours
    });
  },

  onStatusChange(e) {
    this.setData({
      'shop.status': e.detail.value ? 1 : 0
    });
  },

  // ---------------- 多阶梯配送规则动态操作 ----------------
  addDeliveryRule() {
    const rules = [...this.data.deliveryRules];
    let nextMin = 0;
    let nextMax = 3.0;
    let nextMinAmt = 20.0;
    let nextFee = 3.0;

    if (rules.length > 0) {
      const last = rules[rules.length - 1];
      if (last.isUnlimited) {
        // 如果上一条已设为不限距离，提示掌柜
        wx.showToast({
          title: '上一阶梯已设为不限距离，如需新增请先取消其不限状态',
          icon: 'none',
          duration: 2500
        });
        return;
      }
      nextMin = Number(last.maxDistance) || 0;
      nextMax = +(nextMin + 3.0).toFixed(1);
      nextMinAmt = +(Number(last.minOrderAmount || 0) + 10).toFixed(1);
      nextFee = +(Number(last.deliveryFee || 0) + 2).toFixed(1);
    }

    rules.push({
      minDistance: nextMin,
      maxDistance: nextMax,
      isUnlimited: false,
      minOrderAmount: nextMinAmt,
      deliveryFee: nextFee
    });

    this.setData({ deliveryRules: rules });
  },

  // 切换最后一个阶梯的“不限距离(无上限)”状态
  toggleUnlimitedRule(e) {
    const index = Number(e.currentTarget.dataset.index);
    const rules = [...this.data.deliveryRules];
    if (index !== rules.length - 1) return;

    const current = rules[index];
    const willUnlimited = !current.isUnlimited;
    current.isUnlimited = willUnlimited;
    if (willUnlimited) {
      current.maxDistance = '';
    } else {
      current.maxDistance = +(Number(current.minDistance || 0) + 3.0).toFixed(1);
    }

    this.setData({ deliveryRules: rules });
  },

  deleteDeliveryRule(e) {
    const index = Number(e.currentTarget.dataset.index);
    const rules = [...this.data.deliveryRules];
    if (rules.length <= 1) {
      wx.showToast({ title: '至少保留一条配送规则', icon: 'none' });
      return;
    }

    const target = rules[index];
    if (!target) return;

    const rangeText = target.isUnlimited
      ? `${target.minDistance}km 以上（不限距离）`
      : `${target.minDistance}km - ${target.maxDistance}km`;

    wx.showModal({
      title: '删除配送阶梯',
      content: `确定删除「阶梯 ${index + 1}（${rangeText}）」吗？删除后下一阶梯的起始距离会自动衔接。`,
      confirmText: '删除',
      confirmColor: '#FF6B6B',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) return;

        const nextRules = [...this.data.deliveryRules];
        const removed = nextRules[index];
        nextRules.splice(index, 1);

        // 删除后自动衔接：下一阶梯的起始距离继承被删阶梯的起始距离，避免阶梯断档
        const next = nextRules[index];
        if (removed && next) {
          nextRules[index] = { ...next, minDistance: removed.minDistance };
        }

        this.setData({ deliveryRules: nextRules });
        wx.showToast({ title: '已删除该阶梯', icon: 'none' });
      }
    });
  },

  onRuleFieldInput(e) {
    const { index, field } = e.currentTarget.dataset;
    const value = e.detail.value;
    const idx = Number(index);
    const rules = [...this.data.deliveryRules];
    if (!rules[idx]) return;

    rules[idx] = { ...rules[idx], [field]: value };

    // 修改截止距离时自动同步下一阶梯的起始距离，保持阶梯首尾衔接
    if (field === 'maxDistance' && rules[idx + 1]) {
      rules[idx + 1] = { ...rules[idx + 1], minDistance: value };
    }

    this.setData({ deliveryRules: rules });
  },

  // 地图选点获取门店坐标
  chooseLocationOnMap() {
    wx.chooseLocation({
      latitude: this.data.shop.latitude ? parseFloat(this.data.shop.latitude) : undefined,
      longitude: this.data.shop.longitude ? parseFloat(this.data.shop.longitude) : undefined,
      success: (res) => {
        this.setData({
          'shop.address': res.address || res.name,
          'shop.longitude': res.longitude,
          'shop.latitude': res.latitude
        });
        wx.showToast({ title: '已定位门店位置', icon: 'success' });
      },
      fail: (err) => {
        if (err.errMsg && err.errMsg.indexOf('auth deny') >= 0) {
          wx.showModal({
            title: '位置授权',
            content: '需要位置权限以打开地图选择门店位置，请开启权限',
            success: (modalRes) => {
              if (modalRes.confirm) {
                wx.openSetting();
              }
            }
          });
        }
      }
    });
  },

  // 提交保存店铺信息与配送设置
  onSubmitShopInfo(e) {
    if (this.data.submitting) return;

    const values = e.detail.value;
    const { status, id } = this.data.shop;

    if (!values.name || !values.name.trim()) {
      wx.showToast({ title: '请输入店铺名称', icon: 'none' });
      return;
    }
    if (!values.phone || !values.phone.trim()) {
      wx.showToast({ title: '请输入联系电话', icon: 'none' });
      return;
    }
    if (!values.address || !values.address.trim()) {
      wx.showToast({ title: '请输入门店详细地址', icon: 'none' });
      return;
    }
    if (!values.longitude || !values.latitude) {
      wx.showToast({ title: '请填写或从地图选择经纬度', icon: 'none' });
      return;
    }

    // 校验多阶梯配送规则
    const rawRules = this.data.deliveryRules;
    if (!rawRules || rawRules.length === 0) {
      wx.showToast({ title: '请至少配置一条配送阶梯规则', icon: 'none' });
      return;
    }

    const cleanRules = [];
    let hasUnlimited = false;
    let maxDist = 0;
    // 阶梯必须首尾连贯：第 1 段从 0km 起，之后每段的起始距离等于上一段截止距离
    const EPS = 0.01;
    let prevMaxDistance = null;

    for (let i = 0; i < rawRules.length; i++) {
      const r = rawRules[i];
      const minD = parseFloat(r.minDistance);
      const isUnlim = Boolean(r.isUnlimited || r.maxDistance === '' || r.maxDistance === null || parseFloat(r.maxDistance) <= 0);
      const minAmt = parseFloat(r.minOrderAmount) || 0;
      const fee = parseFloat(r.deliveryFee) || 0;

      if (isNaN(minD) || minD < 0) {
        wx.showToast({ title: `第${i + 1}个阶梯的起始距离必须≥0`, icon: 'none' });
        return;
      }

      // 连贯性校验：起点必须承接上一段终点
      if (i === 0) {
        if (Math.abs(minD) > EPS) {
          wx.showToast({ title: '第1个阶梯的起始距离必须为 0km', icon: 'none' });
          return;
        }
      } else if (prevMaxDistance !== null && Math.abs(minD - prevMaxDistance) > EPS) {
        wx.showToast({
          title: `第${i + 1}个阶梯的起始距离必须为 ${prevMaxDistance}km，与上一阶梯衔接`,
          icon: 'none'
        });
        return;
      }

      let maxD = null;
      if (isUnlim) {
        if (i !== rawRules.length - 1) {
          wx.showToast({ title: `不限距离规则必须作为最后一个阶梯配置`, icon: 'none' });
          return;
        }
        hasUnlimited = true;
        maxD = null;
      } else {
        maxD = parseFloat(r.maxDistance);
        if (isNaN(maxD) || maxD <= minD) {
          wx.showToast({ title: `第${i + 1}个阶梯的截止距离必须大于起始距离`, icon: 'none' });
          return;
        }
        if (maxD > maxDist) {
          maxDist = maxD;
        }
      }

      // 记录本段终点，供下一段衔接校验
      prevMaxDistance = maxD;

      cleanRules.push({
        id: r.id || null,
        minDistance: minD,
        maxDistance: maxD,
        minOrderAmount: minAmt,
        deliveryFee: fee,
        sortOrder: i + 1
      });
    }

    const businessHours = (this.data.startTime && this.data.endTime)
      ? `${this.data.startTime} - ${this.data.endTime}`
      : (values.businessHours ? values.businessHours.trim() : (this.data.shop.businessHours || '06:00 - 19:30'));

    const payload = {
      id: id || this.data.shopId || null,
      name: values.name.trim(),
      phone: values.phone.trim(),
      notice: values.notice ? values.notice.trim() : '',
      businessHours: businessHours,
      address: values.address.trim(),
      longitude: parseFloat(values.longitude),
      latitude: parseFloat(values.latitude),
      deliveryRules: cleanRules,
      status: status !== undefined ? status : 1
    };

    this.setData({ submitting: true });
    wx.showLoading({ title: '保存中...' });

    request.put('/shop/admin/update', payload)
      .then(() => {
        wx.showToast({ title: '店铺及配送规则已更新', icon: 'success' });
        setTimeout(() => {
          wx.navigateBack();
        }, 1200);
      })
      .catch(err => {
        wx.showToast({ title: err.message || '更新失败', icon: 'none' });
      })
      .finally(() => {
        this.setData({ submitting: false });
        wx.hideLoading();
      });
  }
});
