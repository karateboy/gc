<template>
  <div>
    <Row class="margin-top-10">
      <Col span="12">
        <Card>
          <p slot="title">
            <Icon type="ios-keypad"></Icon>
            最新校正紀錄
          </p>
          <Form
            :label-width="120"
          >
            <FormItem label="Container Id">
              <strong>
                {{ latestCalibration.containerId }}
              </strong>
            </FormItem>
            <FormItem label="Filename">
              <strong>
                {{ latestCalibration.fileName }}
              </strong>
            </FormItem>
            <FormItem label="Sample Time">
              {{ new Date(latestCalibration._id.time).toLocaleString() }}
            </FormItem>
            <Table v-if="latestCalibration.mtDataList"
                   border
                   :columns="mtColumns"
                   :data="latestCalibration.mtDataList">
            </Table>
          </Form>
        </Card>
      </Col>
      <Col span="12">
        <Card>
          <p slot="title">
            <Icon type="ios-keypad"></Icon>
            校正目標設定
          </p>

          <Row>
            <Col span="6" style="text-align: center">
              測項
            </Col>
            <Col span="6" style="text-align: center">
              低
            </Col>
            <Col span="6" style="text-align: center">
              目標
            </Col>
            <Col span="6" style="text-align: center">
              高
            </Col>
          </Row>
          <Row v-for="target in calibrationTarget" :key="target.mtName" :label="target.mtName">
            <Col span="6">{{ target.mtName }}:</Col>
            <Col span="6">
              <Input v-model.number="target.low"/>
            </Col>
            <Col span="6">
              <Input v-model.number="target.target" @on-change="updateRange(target)"/>
            </Col>
            <Col span="6">
              <Input v-model.number="target.high"/>
            </Col>
          </Row>
          <br>
          <div style="text-align:center">
            <Button
              type="primary"
              size="large"
              @click="updateCalibrationTarget"
            >更新</Button>
          </div>
        </Card>
      </Col>
    </Row>
  </div>
</template>
<style>
.ivu-table .demo-table-error-cell {
  background-color: darkred !important;
  color: white;
}

.ivu-table .demo-table-normal-cell {
  color: black;
}
</style>
<script>
import {getCalibrationTarget, getLatestCalibration, setCalibrationTarget} from "@/api/data";

export default {
  name: "coa",
  components: {},
  async mounted() {
    let res = await getCalibrationTarget();
    if (res.status === 200) {
      this.calibrationTarget = res.data.targets;
    }

    res = await getLatestCalibration();
    if (res.status === 200 && res.data.length > 0) {
      this.latestCalibration = res.data[0];
      this.evaluateCalibration();
      console.info(this.latestCalibration);
    }
  },
  data() {
    return {
      latestCalibration: {
        containerId: "",
        fileName: "",
        _id: {
          monitor: "",
          time: 0,
        }
      },
      mtColumns: [
        {
          title: '測項',
          key: 'mtName',
          align: 'center',
        },
        {
          title: '測值',
          key: 'value',
          align: 'center',
        },
      ],
      calibrationTarget: [
        {
          mtName: 'H2',
          target: 10,
          low: 8,
          high: 12,
        },
        {
          mtName: 'ArO2',
          target: 10,
          low: 8,
          high: 12,
        },
        {
          mtName: 'N2',
          target: 10,
          low: 8,
          high: 12,
        },
        {
          mtName: 'CO',
          target: 10,
          low: 8,
          high: 12,
        },
        {
          mtName: 'CH4',
          target: 10,
          low: 8,
          high: 12,
        },
        {
          mtName: 'CO2',
          target: 10,
          low: 8,
          high: 12,
        },
      ]
    };
  },
  methods: {
    evaluateCalibration() {
      for (let mtData of this.latestCalibration.mtDataList) {
        let target = this.calibrationTarget.find(ct => ct.mtName === mtData.mtName);
        if (!target)
          continue;

        if ((target.high !== undefined && mtData.value > target.high) ||
          (target.low !== undefined && mtData.value < target.low)) {
          mtData.cellClassName = {
            value: 'demo-table-error-cell'
          }
        } else {
          mtData.cellClassName = {
            value: 'demo-table-normal-cell'
          }
        }
      }
      this.latestCalibration.mtDataList = [...this.latestCalibration.mtDataList];
    },
    updateCalibrationTarget() {
      for(let target of this.calibrationTarget) {
        if (target.low === '')
          target.low = undefined;
        if (target.high === '')
          target.high = undefined;
        if(target.target === '')
          target.target = undefined;
      }

      setCalibrationTarget({
        targets: this.calibrationTarget,
      }).then(res => {
        if (res.status === 200) {
          this.$Message.success('更新成功');
        } else {
          this.$Message.error('更新失敗');
        }
      });
      this.evaluateCalibration();
    },
    updateRange(target) {
      if(typeof(target.target) === 'number'){
        target.low = target.target * 0.8;
        target.high = target.target * 1.2;
      }
    }
  }
};
</script>
