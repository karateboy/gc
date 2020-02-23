<style lang="less">
@import "./components/table.less";
</style>

<template>
  <div>
    <Row class="margin-top-10">
      <Col span="24">
        <Card>
          <p slot="title">
            <Icon type="ios-keypad"></Icon>選樣器通道參數
          </p>
          <Row>
            <Col>
              <Card>
                <can-edit-table
                  refs="monitorTab"
                  v-model="monitorList"
                  @on-cell-change="handleCellChange"
                  @on-change="handleChange"
                  :hover-show="true"
                  :editIncell="true"
                  :columns-list="columnsList"
                ></can-edit-table>
              </Card>
            </Col>
          </Row>
        </Card>
      </Col>
    </Row>
  </div>
</template>

<script>
import canEditTable from './components/canEditTable.vue';
import { getMonitors, setMonitor } from '@/api/data';

export default {
  name: 'monitorConfig',
  components: {
    canEditTable
  },
  data() {
    return {
      columnsList: [
        {
          title: '序號',
          type: 'index',
          width: 80,
          align: 'center'
        },
        {
          title: '通道名稱',
          key: 'dp_no',
          editable: true
        },
        {
          title: '操作',
          align: 'center',
          width: 200,
          key: 'handle',
          handle: ['edit']
        }
      ],
      monitorList: []
    };
  },
  methods: {
    getConfig() {
      getMonitors()
        .then(resp => {
          this.monitorList.splice(0, this.monitorList.length);
          for (let monitor of resp.data) {
            this.monitorList.push(monitor);
          }
        })
        .catch(err => {
          alert(err);
        });
    },
    handleNetConnect(state) {
      this.breakConnect = state;
    },
    handleLowSpeed(state) {
      this.lowNetSpeed = state;
    },
    getCurrentData() {
      this.showCurrentTableData = true;
    },
    handleDel(val, index) {
      this.$Message.success('删除了第' + (index + 1) + '行測項');
    },
    handleCellChange(val, index, key) {
      this.handleChange(val, index);
    },
    handleChange(val, index) {
      let id = this.monitorList[index]._id;
      let arg = Object.assign({}, this.monitorList[index]);

      setMonitor(arg)
        .then(resp => {
          const ret = resp.data;
          if (ret.ok) this.$Message.success(`修改通道${id}`);
        })
        .catch(err => {
          alert(err);
        });
    }
  },
  created() {
    this.getConfig();
  }
};
</script>
