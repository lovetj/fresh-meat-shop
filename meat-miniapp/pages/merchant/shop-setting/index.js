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
    // 同城商家配送开关: 1-开通, 0-未开通
    merchantDeliveryEnabled: 1,
    // 外卖配送开关: 1-开通, 0-未开通（需求待定，暂作配置预留）
    takeoutEnabled: 0,
    // 同城多阶梯配送规则列表
    deliveryRules: [
      { minDistance: 0, maxDistance: 3.0, minOrderAmount: 20.0, deliveryFee: 3.0 }
    ],
    // 快递配送配置
    expressEnabled: 0,
    expressFee: '',
    expressRegions: [],
    // 特殊地区配送状态下拉选项：0-可发 1-停发
    deliverOptions: ['可发', '停发'],
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
            rules = res.deliveryRules.map(r => ({
              id: r.id || null,
              minDistance: r.minDistance !== undefined ? r.minDistance : 0,
              maxDistance: (r.maxDistance === null || r.maxDistance === undefined) ? '' : r.maxDistance,
              minOrderAmount: r.minOrderAmount !== undefined ? r.minOrderAmount : 20.0,
              deliveryFee: r.deliveryFee !== undefined ? r.deliveryFee : 3.0
            }));
          } else {
            // 默认初始阶梯规则
            rules = [{
              minDistance: 0,
              maxDistance: 3.0,
              minOrderAmount: 20.0,
              deliveryFee: 3.0
            }];
          }

          // 快递配置：开关、基础运费与特殊地区规则
          const expressRegions = (Array.isArray(res.expressRegions) ? res.expressRegions : []).map(r => ({
            region: r.region || '',
            fee: r.fee === null || r.fee === undefined ? '' : r.fee,
            deliverable: r.deliverable === 0 ? 0 : 1
          }));

          this.setData({
            shop: res,
            shopId: res.id,
            merchantDeliveryEnabled: res.merchantDeliveryEnabled === 0 ? 0 : 1,
            takeoutEnabled: res.takeoutEnabled === 1 ? 1 : 0,
            deliveryRules: rules,
            expressEnabled: res.expressEnabled === 1 ? 1 : 0,
            expressFee: res.expressFee === null || res.expressFee === undefined ? '' : res.expressFee,
            expressRegions,
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
      nextMin = Number(last.maxDistance) || 0;
      nextMax = +(nextMin + 3.0).toFixed(1);
      nextMinAmt = +(Number(last.minOrderAmount || 0) + 10).toFixed(1);
      nextFee = +(Number(last.deliveryFee || 0) + 2).toFixed(1);
    }

    rules.push({
      minDistance: nextMin,
      maxDistance: nextMax,
      minOrderAmount: nextMinAmt,
      deliveryFee: nextFee
    });

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

    const rangeText = `${target.minDistance}km - ${target.maxDistance}km`;

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

  // ---------------- 外卖配送开关 ----------------
  onTakeoutEnabledChange(e) {
    this.setData({ takeoutEnabled: e.detail.value ? 1 : 0 });
  },

  // ---------------- 同城商家配送开关 ----------------
  onMerchantDeliveryChange(e) {
    const enabled = e.detail.value ? 1 : 0;
    if (enabled === 0) {
      wx.showToast({ title: '已关闭商家配送，顾客仅能选自取或快递', icon: 'none' });
    }
    this.setData({ merchantDeliveryEnabled: enabled });
  },

  // ---------------- 快递配送配置 ----------------
  onExpressEnabledChange(e) {
    this.setData({ expressEnabled: e.detail.value ? 1 : 0 });
  },

  onExpressFeeInput(e) {
    this.setData({ expressFee: e.detail.value });
  },

  addExpressRegion() {
    const regions = [...this.data.expressRegions];
    regions.push({ region: '', fee: '', deliverable: 1 });
    this.setData({ expressRegions: regions });
  },

  deleteExpressRegion(e) {
    const index = Number(e.currentTarget.dataset.index);
    const target = this.data.expressRegions[index];
    if (!target) return;

    const regionName = (target.region || '').trim() || `第 ${index + 1} 行地区`;

    wx.showModal({
      title: '删除特殊地区',
      content: `确定删除「${regionName}」的快递规则吗？删除后该地区将按基础运费计费。`,
      confirmText: '删除',
      confirmColor: '#FF6B6B',
      cancelText: '取消',
      success: (res) => {
        if (!res.confirm) return;
        const regions = this.data.expressRegions.filter((_, i) => i !== index);
        this.setData({ expressRegions: regions });
        wx.showToast({ title: '已删除该地区', icon: 'none' });
      }
    });
  },

  /** 特殊地区：地图选择省级/直辖市，仅取第一级省份，忽略市与区县 */
  onExpressRegionPick(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const value = e.detail.value || [];
    const province = (value[0] || '').trim();
    if (!province) return;
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], region: province };
    this.setData({ expressRegions: regions });
  },

  onExpressRegionInput(e) {
    const { index, field } = e.currentTarget.dataset;
    const idx = Number(index);
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], [field]: e.detail.value };
    this.setData({ expressRegions: regions });
  },

  // 特殊地区「可发/停发」下拉选择
  onExpressRegionDeliverableChange(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const selected = Number(e.detail.value);
    const regions = [...this.data.expressRegions];
    if (!regions[idx]) return;
    regions[idx] = { ...regions[idx], deliverable: selected === 1 ? 0 : 1 };
    this.setData({ expressRegions: regions });
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

  /**
   * 校验并清洗同城配送阶梯规则
   * 阶梯必须首尾连贯：第 1 段从 0km 起，之后每段的起始距离等于上一段截止距离
   * @returns {Array|null} 校验失败返回 null
   */
  buildCleanRules(rawRules) {
    const cleanRules = [];
    const EPS = 0.01;
    let prevMaxDistance = null;

    for (let i = 0; i < rawRules.length; i++) {
      const r = rawRules[i];
      const minD = parseFloat(r.minDistance);
      const minAmt = parseFloat(r.minOrderAmount) || 0;
      const fee = parseFloat(r.deliveryFee) || 0;

      if (isNaN(minD) || minD < 0) {
        wx.showToast({ title: `第${i + 1}个阶梯的起始距离必须≥0`, icon: 'none' });
        return null;
      }

      // 连贯性校验：起点必须承接上一段终点
      if (i === 0) {
        if (Math.abs(minD) > EPS) {
          wx.showToast({ title: '第1个阶梯的起始距离必须为 0km', icon: 'none' });
          return null;
        }
      } else if (prevMaxDistance !== null && Math.abs(minD - prevMaxDistance) > EPS) {
        wx.showToast({
          title: `第${i + 1}个阶梯的起始距离必须为 ${prevMaxDistance}km，与上一阶梯衔接`,
          icon: 'none'
        });
        return null;
      }

      const maxD = parseFloat(r.maxDistance);
      if (isNaN(maxD) || maxD <= minD) {
        wx.showToast({ title: `第${i + 1}个阶梯的截止距离必须大于起始距离`, icon: 'none' });
        return null;
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

    return cleanRules;
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

    // 校验多阶梯配送规则（仅同城商家配送开启时校验）
    const merchantDeliveryEnabled = this.data.merchantDeliveryEnabled === 1 ? 1 : 0;
    let cleanRules = [];

    if (merchantDeliveryEnabled === 1) {
      const rawRules = this.data.deliveryRules || [];
      if (rawRules.length === 0) {
        wx.showToast({ title: '请至少配置一条配送阶梯规则', icon: 'none' });
        return;
      }
      cleanRules = this.buildCleanRules(rawRules);
      if (!cleanRules) return;
    }

    const businessHours = (this.data.startTime && this.data.endTime)
      ? `${this.data.startTime} - ${this.data.endTime}`
      : (values.businessHours ? values.businessHours.trim() : (this.data.shop.businessHours || '06:00 - 19:30'));

    // 校验快递配送配置
    const takeoutEnabled = this.data.takeoutEnabled === 1 ? 1 : 0;
    const expressEnabled = this.data.expressEnabled === 1 ? 1 : 0;
    const expressFeeNum = parseFloat(this.data.expressFee);
    if (expressEnabled === 1 && this.data.expressFee !== ''
      && (isNaN(expressFeeNum) || expressFeeNum < 0)) {
      wx.showToast({ title: '快递基础运费需为不小于 0 的数字', icon: 'none' });
      return;
    }

    const cleanRegions = [];
    for (const r of this.data.expressRegions) {
      const regionName = (r.region || '').trim();
      if (!regionName) {
        wx.showToast({ title: '请填写特殊地区名称，或删除该行', icon: 'none' });
        return;
      }
      const feeNum = parseFloat(r.fee);
      if (r.fee !== '' && (isNaN(feeNum) || feeNum < 0)) {
        wx.showToast({ title: `「${regionName}」的运费需为不小于 0 的数字`, icon: 'none' });
        return;
      }
      cleanRegions.push({
        region: regionName,
        fee: (r.fee === '' || isNaN(feeNum)) ? null : feeNum,
        deliverable: r.deliverable === 0 ? 0 : 1
      });
    }

    const payload = {
      id: id || this.data.shopId || null,
      name: values.name.trim(),
      phone: values.phone.trim(),
      notice: values.notice ? values.notice.trim() : '',
      businessHours: businessHours,
      address: values.address.trim(),
      longitude: parseFloat(values.longitude),
      latitude: parseFloat(values.latitude),
      merchantDeliveryEnabled,
      takeoutEnabled,
      deliveryRules: cleanRules,
      expressEnabled,
      expressFee: isNaN(expressFeeNum) ? 0 : expressFeeNum,
      expressRegions: cleanRegions,
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
