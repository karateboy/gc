<template>
  <div>
    <Row>
      <i-col :xs="12" :md="8" :lg="4" key="selector" style="height: 120px;padding-bottom: 10px;">
        <infor-card shadow :color="selector.color" :icon="selector.icon" :icon-size="36">
          <Card :bordered="false" :dis-hover="true" :title="selector.title" :key="selector._id">
            <p>通道:{{selector._id}}</p>
            <p>{{ selector.dp_no}}</p>
          </Card>
        </infor-card>
      </i-col>
    </Row>
    <Row>
      <Card>
        <Form :model="formItem" :label-width="80">
          <FormItem label="切換至">
            <Select v-model="formItem.monitor" filterable>
              <Option v-for="item in monitorList" :value="item._id" :key="item._id">{{ item.dp_no }}</Option>
            </Select>
          </FormItem>
          <FormItem>
            <Button type="primary" @click="setCurrentSelector">設定</Button>
            <Button style="margin-left: 8px">取消</Button>
          </FormItem>
        </Form>
      </Card>
    </Row>
  </div>
</template>
<style scoped>
</style>
<script>
import InforCard from "_c/info-card";
import config from "@/config";
const baseUrl =
  process.env.NODE_ENV === "development"
    ? config.baseUrl.dev
    : config.baseUrl.pro;

import { getMonitors, getCurrentMonitor, setCurrentMonitor } from "@/api/data";
export default {
  name: "selector",
  components: {
    InforCard
  },
  mounted() {
    getMonitors()
      .then(resp => {
        this.monitorList.splice(0, this.monitorList.length);
        for (let mt of resp.data) {
          this.monitorList.push(mt);
        }
      })
      .catch(err => {
        alert(err);
      });
    this.refreshCurrentSelector();
  },
  data() {
    return {
      monitorList: [],
      selector: {
        _id: "default",
        dp_no: "#2",
        icon: "ios-speedometer",
        color: "#ff0000",
        title: "選樣器通道"
      },
      formItem: {
        monitor: 1
      }
    };
  },
  computed: {},
  methods: {
    setCurrentSelector() {
      setCurrentMonitor(this.formItem.monitor)
        .then(resp => {
          this.$Message.success("切換成功");
          this.refreshCurrentSelector();
        })
        .catch(err => {
          alert(err);
        });
    },
    refreshCurrentSelector() {
      getCurrentMonitor()
        .then(resp => {
          this.selector = Object.assign(
            {
              icon: "ios-speedometer",
              color: "#ff0000",
              title: "選樣器通道"
            },
            resp.data
          );
        })
        .catch(err => {
          alert(err);
        });
    }
  }
};
</script>
