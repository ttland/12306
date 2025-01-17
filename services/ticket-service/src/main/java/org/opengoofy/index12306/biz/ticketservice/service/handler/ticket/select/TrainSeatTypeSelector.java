/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SeatStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.toolkit.StationCalculateUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 购票时列车座位选择器
 *
 * @公众号：马丁玩编程，回复：加群，添加马哥微信（备注：12306）获取项目资料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TrainSeatTypeSelector {

    private final SeatMapper seatMapper;
    private final TrainStationMapper trainStationMapper;
    private final UserRemoteService userRemoteService;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final AbstractStrategyChoose abstractStrategyChoose;

    public List<TrainPurchaseTicketRespDTO> select(Integer trainType, PurchaseTicketReqDTO requestParam) {
        // TODO 后续逻辑全部转换为 LUA 缓存原子操作
        List<PurchaseTicketPassengerDetailDTO> passengerDetails = requestParam.getPassengers();
        // 如果多个乘车人选择了不同座位，需要拆分处理
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = passengerDetails.stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> actualResult = new ArrayList<>();
        seatTypeMap.forEach((seatType, passengerSeatDetails) -> {
            String buildStrategyKey = VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
            SelectSeatDTO selectSeatDTO = SelectSeatDTO.builder()
                    .seatType(seatType)
                    .passengerSeatDetails(passengerSeatDetails)
                    .requestParam(requestParam)
                    .build();
            List<TrainPurchaseTicketRespDTO> aggregationResult = abstractStrategyChoose.chooseAndExecuteResp(buildStrategyKey, selectSeatDTO);
            actualResult.addAll(aggregationResult);
        });
        if (CollUtil.isEmpty(actualResult)) {
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }
        List<String> passengerIds = actualResult.stream()
                .map(TrainPurchaseTicketRespDTO::getPassengerId)
                .collect(Collectors.toList());
        Result<List<PassengerRespDTO>> passengerRemoteResult;
        List<PassengerRespDTO> passengerRemoteResultList;
        try {
            // 查询乘车人信息并赋值
            passengerRemoteResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
            if (passengerRemoteResult.isSuccess() && CollUtil.isNotEmpty(passengerRemoteResultList = passengerRemoteResult.getData())) {
                actualResult.forEach(each -> {
                    String passengerId = each.getPassengerId();
                    passengerRemoteResultList.stream()
                            .filter(item -> Objects.equals(item.getId(), passengerId))
                            .findFirst()
                            .ifPresent(passenger -> {
                                each.setIdCard(passenger.getIdCard());
                                each.setPhone(passenger.getPhone());
                                each.setUserType(passenger.getDiscountType());
                                each.setIdType(passenger.getIdType());
                                each.setRealName(passenger.getRealName());
                            });
                    // 查询车次出发站-终点站座位价格
                    LambdaQueryWrapper<TrainStationPriceDO> lambdaQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                            .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                            .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                            .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                            .eq(TrainStationPriceDO::getSeatType, each.getSeatType());
                    TrainStationPriceDO trainStationPriceDO = trainStationPriceMapper.selectOne(lambdaQueryWrapper);
                    each.setAmount(trainStationPriceDO.getPrice());
                });
            }
        } catch (Throwable ex) {
            log.error("用户服务远程调用查询乘车人相信信息错误", ex);
            throw ex;
        }
        // 获取扣减开始站点和目的站点及中间站点信息
        LambdaQueryWrapper<TrainStationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationDO.class)
                .eq(TrainStationDO::getTrainId, requestParam.getTrainId());
        List<TrainStationDO> trainStationDOList = trainStationMapper.selectList(queryWrapper);
        List<String> trainStationAllList = trainStationDOList.stream().map(TrainStationDO::getDeparture).collect(Collectors.toList());
        List<RouteDTO> routeList = StationCalculateUtil.throughStation(trainStationAllList, requestParam.getDeparture(), requestParam.getArrival());
        // 锁定座位车票库存
        actualResult.forEach(each -> routeList.forEach(item -> {
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, requestParam.getTrainId())
                    .eq(SeatDO::getCarriageNumber, each.getCarriageNumber())
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getSeatNumber, each.getSeatNumber());
            SeatDO updateSeatDO = SeatDO.builder().seatStatus(SeatStatusEnum.LOCKED.getCode()).build();
            seatMapper.update(updateSeatDO, updateWrapper);
        }));
        return actualResult;
    }
}
