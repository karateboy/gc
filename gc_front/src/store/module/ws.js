export default {
  state: {
    hasAlarm: false
  },
  mutations: {
    clearAlarm(state) {
      state.hasAlarm = false;
    }
  },
  getters: {
    hasUnhandledAlarm: state => state.hasAlarm
  },
  actions: {
    // 此方法用来获取未读消息条数，接口只返回数值，不返回消息列表
    getUnreadMessageCount({ state, commit }) { },
    // 获取消息列表，其中包含未读、已读、回收站三个列表
    getMessageList({ state, commit }) {
      return new Promise((resolve, reject) => {
        getMessage().then(res => {
          const { unread, readed, trash } = res.data
          commit('setMessageUnreadList', unread.sort((a, b) => new Date(b.create_time) - new Date(a.create_time)))
          commit('setMessageReadedList', readed.map(_ => {
            _.loading = false
            return _
          }).sort((a, b) => new Date(b.create_time) - new Date(a.create_time)))
          commit('setMessageTrashList', trash.map(_ => {
            _.loading = false
            return _
          }).sort((a, b) => new Date(b.create_time) - new Date(a.create_time)))
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    }
  }
}
