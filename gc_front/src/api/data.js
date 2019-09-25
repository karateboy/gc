import axios from '@/libs/api.request'

export const getMonitors = () => {
  return axios.request({
    url: 'monitor',
    method: 'get'
  })
}

export const setMonitor = (data) => {
  return axios.request({
    url: 'monitor',
    method: 'post',
    data
  })
}
export const getMonitorTypes = () => {
  return axios.request({
    url: 'monitor_type',
    method: 'get'
  })
}

export const getHistoryData = ({ monitor, monitorTypes, start, end }) => {
  return axios.request({
    url: 'history_data',
    method: 'get',
    params: {
      monitor, monitorTypes, start, end
    }
  })
}

export const getHistoryTrend = ({ monitors, monitorTypes, start, end }) => {
  return axios.request({
    url: 'history_trend',
    method: 'get',
    params: {
      monitors, monitorTypes, start, end
    }
  })
}

export const getRealtimeData = () => {
  return axios.request({
    url: 'realtime_data',
    method: 'get',
    params: {}
  })
}

export const getCurrentMonitor = () => {
  return axios.request({
    url: 'current_monitor',
    method: 'get',
    params: {}
  })
}

export const setCurrentMonitor = (id) => {
  return axios.request({
    url: 'current_monitor',
    method: 'post',
    params: { id }
  })
}

export const setMonitorType = (data) => {
  return axios.request({
    url: 'monitor_type',
    method: 'post',
    data
  })
}
export const getHistoryTrendBoxPlot = ({ monitors, monitorTypes, start, end }) => {
  return axios.request({
    url: 'history_trend_boxplot',
    method: 'post',
    params: {
      monitors, monitorTypes, start, end
    }
  })
}

export const getTableData = () => {
  return axios.request({
    url: 'get_table_data',
    method: 'get'
  })
}

export const getDragList = () => {
  return axios.request({
    url: 'get_drag_list',
    method: 'get'
  })
}

export const errorReq = () => {
  return axios.request({
    url: 'error_url',
    method: 'post'
  })
}

export const uploadImg = formData => {
  return axios.request({
    url: 'image/upload',
    data: formData
  })
}

export const getOrgData = () => {
  return axios.request({
    url: 'get_org_data',
    method: 'get'
  })
}

export const getTreeSelectData = () => {
  return axios.request({
    url: 'get_tree_select_data',
    method: 'get'
  })
}
