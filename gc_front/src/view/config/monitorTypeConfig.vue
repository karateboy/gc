<style lang="less">
@import "./components/table.less";
</style>

<template>
  <div>
    <Row class="margin-top-10">
      <Card>
        <p slot="title">
          <Icon type="ios-keypad"></Icon>測項參數
        </p>
        <can-edit-table
          refs="mtTable"
          v-model="monitorTypeList"
          @on-cell-change="handleCellChange"
          @on-change="handleChange"
          :hover-show="true"
          :editIncell="true"
          :columns-list="columnsList"
        ></can-edit-table>
      </Card>
    </Row>
    <Row class="margin-top-10">
      <Card>
        <p slot="title">
          <Icon type="ios-keypad"></Icon>無資料警告時間
        </p>
        <Form :model="formItem" :label-width="80">
          <FormItem label="時間 (分):">
            <Slider v-model="formItem.dataPeriod" show-input></Slider>
          </FormItem>
          <FormItem>
            <Button type="primary" @click.prevent="setDataPeriod">更新</Button>
          </FormItem>
        </Form>
      </Card>
    </Row>
  </div>
</template>

<script>
import axios from "axios";
import canEditTable from "./components/canEditTable.vue";
import { getMonitorTypes, setMonitorType } from "@/api/data";
export default {
  name: "monitorTypeConfig",
  components: {
    canEditTable
  },
  data() {
    return {
      columnsList: [
        {
          title: "序號",
          type: "index",
          width: 80,
          align: "center"
        },
        {
          title: "名稱",
          key: "desp",
          editable: true
        },
        {
          title: "單位",
          key: "unit",
          editable: true
        },
        {
          title: "警報值",
          key: "std_internal",
          editable: true
        },
        {
          title: "超高警報值",
          key: "std_law",
          editable: true
        },
        {
          title: "小數點位數",
          key: "prec",
          editable: true
        },
        {
          title: "操作",
          align: "center",
          width: 200,
          key: "handle",
          handle: ["edit"]
        }
      ],
      monitorTypeList: [],
      formItem: {
        dataPeriod: 30
      }
    };
  },
  methods: {
    getConfig() {
      getMonitorTypes()
        .then(resp => {
          this.monitorTypeList.splice(0, this.monitorTypeList.length);
          for (let mt of resp.data) {
            this.monitorTypeList.push(mt);
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
      this.$Message.success("删除了第" + (index + 1) + "行測項");
    },
    handleCellChange(val, index, key) {
      return this.handleChange(val, index);
    },
    handleChange(val, index) {
      let id = this.monitorTypeList[index]._id;
      let arg = Object.assign({}, this.monitorTypeList[index]);
      arg.prec = parseInt(arg.prec);
      arg.std_internal = parseFloat(arg.std_internal);
      arg.std_law = parseFloat(arg.std_law);

      setMonitorType(arg)
        .then(resp => {
          const ret = resp.data;
          if (ret.ok) this.$Message.success(`修改${id}`);
        })
        .catch(err => {
          alert(err);
        });
    },
    getDataPeriod() {
      axios.get("/dataPeriod").then(res => {
        const ret = res.data;
        this.formItem.dataPeriod = ret;
      });
      return 0;
    },
    setDataPeriod() {
      axios
        .post("/dataPeriod", { value: this.formItem.dataPeriod })
        .then(res => {
          const ret = res.data;
          if (ret.ok) this.$Message.success("成功");
          else this.$Message.success("失敗");
        })
        .catch(err => {
          alert(err);
        });
    }
  },
  created() {
    this.getConfig();
    this.getDataPeriod();
  }
};
</script>
