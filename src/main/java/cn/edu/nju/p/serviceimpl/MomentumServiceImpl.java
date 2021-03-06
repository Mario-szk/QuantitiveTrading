package cn.edu.nju.p.serviceimpl;

import cn.edu.nju.p.dao.StockDao;
import cn.edu.nju.p.po.StockPO;
import cn.edu.nju.p.service.strategy.MomentumService;
import cn.edu.nju.p.utils.CalculateHelper;
import cn.edu.nju.p.utils.DateHelper;
import cn.edu.nju.p.utils.DoubleUtils;
import cn.edu.nju.p.utils.StockHelper;
import cn.edu.nju.p.utils.holiday.Holidays;
import cn.edu.nju.p.utils.redis.StockRedisDataUtils;
import cn.edu.nju.p.vo.MomentumResultVO;
import cn.edu.nju.p.vo.MomentumVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MomentumService的实现类，实现了动量策略，使用简单收益率作为动量因子
 */
@Service
public class MomentumServiceImpl implements MomentumService {

    @Autowired
    private StockDao stockDao;

    @Autowired
    private CalculateHelper helper;

    @Autowired
    private Holidays holidays;

    @Autowired
    private StockHelper stockHelper;

    @Autowired
    private StockRedisDataUtils redisDataUtils;


    /**
     * 获取基本的数据
     * @param momentumVO 数据输入
     * @return 返回显示结果的vo
     */
    @Override
    @Cacheable("getMomentumResult")
    public MomentumResultVO getResult(MomentumVO momentumVO) {

        LocalDate beginDate = momentumVO.getBeginDate();
        LocalDate endDate = momentumVO.getEndDate();
        int formativeDayNum = momentumVO.getDayNumFormative();
        int holdingDayNum = momentumVO.getDayNumHolding();

        //获取股票池
        List<String> stockPool = momentumVO.getStockPool()==null?getRecommendPool():momentumVO.getStockPool();

        //获得开始日期-形成期的具体日期，作为判断股票停牌的开始日期
        LocalDate virtualBeginDate = holidays.getIntervalEffectiveDate(beginDate,formativeDayNum);

        //过滤股票池
//        stockPool = stockHelper.filterStockPool(stockPool,virtualBeginDate,endDate);

        //开始日期和结束日期之间的有效日期
        List<LocalDate> betweenDates = holidays.getBetweenDatesAndFilter(beginDate, endDate, a -> true);

        List<Double> dailyFiledRates = new ArrayList<>();
        List<Double> accumulationFiledRates = new ArrayList<>();

        //持有的股票
        List<String> stockToHold;

        int primaryMoney = 100000;
        int totalMoney = 100000;

        LocalDate beginValidDate = betweenDates.get(0);
        LocalDate virBeginDate = holidays.getIntervalEffectiveDate(beginValidDate, formativeDayNum);
        stockToHold = getWinnerStock(virBeginDate, holidays.getLastValidDate(beginValidDate), stockPool);

        double beginClose = stockHelper.calculateTotalClose(stockToHold, beginValidDate); //初始总收盘价
        int moneyPer100 = new BigDecimal(100 * beginClose).intValue();

        int nums = primaryMoney / moneyPer100;
        int leftMoney = primaryMoney - nums * moneyPer100;
        int lastMoney = primaryMoney;

        //计算策略收益率
        for (int i = 1; i < betweenDates.size(); i++) {
            LocalDate currentDate = betweenDates.get(i);
            if (i % holdingDayNum == 0) {
                //整除 重新选择股票
                virBeginDate = holidays.getIntervalEffectiveDate(currentDate, formativeDayNum);
                stockToHold = getWinnerStock(virBeginDate, holidays.getLastValidDate(currentDate), stockPool);
                beginClose = stockHelper.calculateTotalClose(stockToHold, betweenDates.get(i - 1));
                moneyPer100 = new BigDecimal(100 * beginClose).intValue();
                nums = lastMoney / moneyPer100;
                leftMoney = lastMoney - nums * moneyPer100;
            }

            double totalClose = stockHelper.calculateTotalClose(stockToHold, currentDate);
            totalMoney = new BigDecimal(totalClose * 100).intValue() * nums + leftMoney; //当前总资产

            dailyFiledRates.add(DoubleUtils.formatDouble((double) (totalMoney - lastMoney) / lastMoney, 4));
            accumulationFiledRates.add(DoubleUtils.formatDouble((double) (totalMoney - primaryMoney) / primaryMoney, 4));
            lastMoney = totalMoney;
        }

        //基准收益率 取股票池中所有股票的平均收益
        ArrayList<List<Double>> maps = stockHelper.getPrimaryRate(stockPool, betweenDates);

        helper.setDailyYieldRates(dailyFiledRates);
        helper.setAccumulationYieldRates(accumulationFiledRates);
        helper.setDailyPrimaryRates(maps.get(0));
        helper.setAccumulationPrimaryRates(maps.get(1));

        Map<Double, Integer> rateFrequency = helper.getRateFrequency(holdingDayNum);

        double beta = helper.getBeta();
        double alpha = helper.getAlpha();
        double primaryYearYield = helper.getPrimaryYearRate();
        double yearYield = helper.getFieldYearRate();
        double shapeRatio = helper.getShapeRatio();
        double maxDrawDown = helper.getMaxDrawDown();

        List<String> dateList = betweenDates.parallelStream().map(LocalDate::toString).sorted().collect(Collectors.toList());

        return new MomentumResultVO(maps.get(1),accumulationFiledRates,dateList,rateFrequency,beta,alpha,shapeRatio,maxDrawDown,yearYield,primaryYearYield);
    }

    /**
     * 获取自推荐的股票池.默认就是所有股票
     * @return 返回自推荐的股票池
     */
    private List<String> getRecommendPool() {

        return stockHelper.getRecommendStock();
    }

    /**
     * 获取winner股票 例如获取10-20到11-20的前20%收益率最高的股票
     * @param beginDate 开始日期
     * @param endDate 结束日期
     * @param stockPool 股票池
     * @return 返回前20%收益的winner
     */
    public List<String> getWinnerStock(LocalDate beginDate,LocalDate endDate,List<String> stockPool){

        Map<String, Double> fieldRates = new LinkedHashMap<>();
        stockPool.forEach(stockCode -> fieldRates.put(stockCode,countRate(beginDate,endDate,stockCode)));

        //对收益率进行排序
        List<Map.Entry<String, Double>> rateList = new ArrayList<>(fieldRates.entrySet());
        rateList.sort((rate1, rate2) -> new BigDecimal(rate2.getValue()).compareTo(new BigDecimal(rate1.getValue())));

        int winnerNum = rateList.size() / 5;
        return rateList.subList(0,winnerNum)
                .parallelStream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 计算股票在开始时间和结束时间内的简单收益率 计算公式为(结束日期当天adjClose-开始日期前日adjClose)/开始日期前日adjClose
     * @param beginDate 开始日期
     * @param endDate 结束日期
     * @param stockCode 股票代码
     * @return 收益率
     */
    public double countRate(LocalDate beginDate,LocalDate endDate,String stockCode){

        try {
            StockPO beginPo = redisDataUtils.getStockPO(stockCode, beginDate);
            StockPO endPo = redisDataUtils.getStockPO(stockCode, endDate);
            double beginClose = beginPo.getClose();
            double endClose = endPo.getClose();
            return (endClose - beginClose) / beginClose;
        } catch (NullPointerException ne) {
            return -99;
        }
    }


}
