/**
 * 前端购物车本地缓存模块
 * 
 * 核心特性：
 * 1. 用户与门店双维度严格隔离 (MEAT_CART_{userId}_{shopId})
 * 2. 持久化缓存不过期
 * 3. 用户退出登录时同步彻底清空缓存
 */
const auth = require('./auth');
const { formatImageUrl } = require('./util');

const CART_PREFIX = 'MEAT_CART_';

/**
 * 获取当前用户标识
 * 注意：后端登录/用户信息返回的用户标识字段是 userId（LoginResultVO），
 * 历史代码只读 id 会永远取不到，导致所有购物车都落在 guest 名下 ——
 * 退出登录后购物车依然展示、不同账号共用同一购物车。
 */
function getCurrentUserId() {
  const user = auth.getUserInfo();
  if (!user) return 'guest';
  const uid = user.userId || user.id;
  return uid ? String(uid) : 'guest';
}

/**
 * 构建隔离 Key
 * @param {string} shopId 门店ID
 * @param {string} [userId] 用户ID（可选，默认取当前登录用户）
 */
function buildKey(shopId, userId) {
  const uid = userId || getCurrentUserId();
  const sid = shopId || 'default';
  return `${CART_PREFIX}${uid}_${sid}`;
}

/**
 * 历史版本购物车使用 guest 作为用户标识，这里把残留数据归到当前登录用户名下，
 * 避免退出登录后 guest 购物车仍然被读取出来（同时清理脏数据）
 */
function migrateLegacyGuestCart(shopId) {
  const user = auth.getUserInfo();
  const uid = user && (user.userId || user.id);
  if (!uid) return;

  const sid = shopId || 'default';
  const legacyKey = `${CART_PREFIX}guest_${sid}`;
  const targetKey = `${CART_PREFIX}${uid}_${sid}`;

  try {
    const legacy = wx.getStorageSync(legacyKey);
    if (!legacy) return;

    const current = wx.getStorageSync(targetKey);
    if ((!Array.isArray(current) || current.length === 0) && Array.isArray(legacy) && legacy.length) {
      wx.setStorageSync(targetKey, legacy);
    }
    wx.removeStorageSync(legacyKey);
  } catch (e) {}
}

const cart = {
  /**
   * 获取指定门店的购物车列表
   * @param {string} shopId 
   * @returns {Array} cartList
   */
  getCart(shopId) {
    if (!shopId) return [];
    try {
      migrateLegacyGuestCart(shopId);
      const key = buildKey(shopId);
      const list = wx.getStorageSync(key);
      return Array.isArray(list) ? list : [];
    } catch (e) {
      console.error('获取购物车缓存失败', e);
      return [];
    }
  },

  /**
   * 保存购物车列表
   * @param {string} shopId 
   * @param {Array} list 
   */
  saveCart(shopId, list) {
    if (!shopId) return;
    try {
      const key = buildKey(shopId);
      wx.setStorageSync(key, list || []);
    } catch (e) {
      console.error('写入购物车缓存失败', e);
    }
  },

  /**
   * 加入购物车
   * @param {string} shopId 门店ID
   * @param {Object} product 商品对象
   * @param {string} specInfo 规格说明
   * @param {number} quantity 购买数量
   * @returns {Object} { success, totalCount, totalAmount, message }
   */
  addToCart(shopId, product, specInfo = '', quantity = 1) {
    if (!shopId || !product || !product.id) {
      return { success: false, message: '商品信息无效' };
    }

    const buyQty = Math.max(1, Number(quantity) || 1);
    const cleanSpec = (specInfo && specInfo.trim()) ? specInfo.trim() : (product.unit || '默认规格');
    const itemId = `${product.id}__${cleanSpec}`;

    const list = this.getCart(shopId);
    const index = list.findIndex(item => item.id === itemId);

    let currentQtyInCart = 0;
    if (index > -1) {
      currentQtyInCart = list[index].quantity;
    }

    const newQty = currentQtyInCart + buyQty;
    const stock = Number(product.stock) || 999;
    if (newQty > stock) {
      return {
        success: false,
        message: `库存不足，该规格购物车中已有 ${currentQtyInCart} 件，最多可购买 ${stock} 件`
      };
    }

    const itemPrice = Number(product.price) || 0;

    if (index > -1) {
      list[index].quantity = newQty;
      list[index].subtotal = (newQty * itemPrice).toFixed(2);
    } else {
      list.push({
        id: itemId,
        productId: product.id,
        name: product.name,
        coverImage: formatImageUrl(product.coverImage),
        price: itemPrice.toFixed(2),
        originalPrice: product.originalPrice ? Number(product.originalPrice).toFixed(2) : '',
        unit: product.unit || '斤',
        specInfo: cleanSpec,
        quantity: newQty,
        stock: stock,
        shopId: shopId,
        subtotal: (newQty * itemPrice).toFixed(2)
      });
    }

    this.saveCart(shopId, list);
    const summary = this.getCartSummary(shopId);
    return {
      success: true,
      totalCount: summary.totalCount,
      totalAmount: summary.totalAmount,
      cartList: list
    };
  },

  /**
   * 变更购物车商品数量（支持增加或减少）
   * @param {string} shopId 
   * @param {string} itemId 
   * @param {number} delta 变化量，如 +1 或 -1
   */
  changeQuantity(shopId, itemId, delta) {
    const list = this.getCart(shopId);
    const index = list.findIndex(item => item.id === itemId);
    if (index === -1) return this.getCartSummary(shopId);

    const target = list[index];
    const newQty = target.quantity + delta;

    if (newQty <= 0) {
      list.splice(index, 1);
    } else {
      if (newQty > target.stock) {
        wx.showToast({ title: `仅剩 ${target.stock} 件库存啦～`, icon: 'none' });
        return this.getCartSummary(shopId);
      }
      target.quantity = newQty;
      target.subtotal = (newQty * Number(target.price)).toFixed(2);
    }

    this.saveCart(shopId, list);
    return this.getCartSummary(shopId);
  },

  /**
   * 移除指定购物车条目
   * @param {string} shopId 
   * @param {string} itemId 
   */
  removeItem(shopId, itemId) {
    const list = this.getCart(shopId);
    const nextList = list.filter(item => item.id !== itemId);
    this.saveCart(shopId, nextList);
    return this.getCartSummary(shopId);
  },

  /**
   * 清空指定门店的购物车
   * @param {string} shopId 
   */
  clearCart(shopId) {
    if (!shopId) return;
    try {
      const key = buildKey(shopId);
      wx.removeStorageSync(key);
    } catch (e) {
      console.error('清空门店购物车失败', e);
    }
  },

  /**
   * 获取购物车汇总数据
   * @param {string} shopId 
   */
  getCartSummary(shopId) {
    const list = this.getCart(shopId);
    let totalCount = 0;
    let totalAmount = 0;

    for (const item of list) {
      const q = Number(item.quantity) || 0;
      const p = Number(item.price) || 0;
      totalCount += q;
      totalAmount += q * p;
    }

    return {
      totalCount,
      totalAmount: totalAmount.toFixed(2),
      cartList: list
    };
  },

  /**
   * 退出登录时，同步清空所有购物车缓存（或清空当前用户的购物车）
   */
  clearAllCart() {
    try {
      const res = wx.getStorageInfoSync();
      if (res && res.keys) {
        for (const k of res.keys) {
          if (k.startsWith(CART_PREFIX)) {
            wx.removeStorageSync(k);
          }
        }
      }
    } catch (e) {
      console.error('清空购物车缓存失败', e);
    }
  }
};

module.exports = cart;
