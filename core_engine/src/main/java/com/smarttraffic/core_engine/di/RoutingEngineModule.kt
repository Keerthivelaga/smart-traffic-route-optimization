package com.smarttraffic.core_engine.di

import com.smarttraffic.core_engine.data.routing.CostFunctionBuilder
import com.smarttraffic.core_engine.data.routing.DefaultIncidentPenaltyModel
import com.smarttraffic.core_engine.data.routing.DefaultPersonalizationLayer
import com.smarttraffic.core_engine.data.routing.DefaultPredictiveWeightAdjuster
import com.smarttraffic.core_engine.data.routing.DefaultReliabilityScorer
import com.smarttraffic.core_engine.data.routing.DefaultRoadClassWeighting
import com.smarttraffic.core_engine.data.routing.DefaultRouteCandidateGenerator
import com.smarttraffic.core_engine.data.routing.DefaultRouteRankingModel
import com.smarttraffic.core_engine.data.routing.DefaultTrafficPenaltyModel
import com.smarttraffic.core_engine.data.routing.DefaultTurnCostModel
import com.smarttraffic.core_engine.data.routing.DefaultWeatherPenaltyModel
import com.smarttraffic.core_engine.data.routing.DirectedRoadGraph
import com.smarttraffic.core_engine.data.routing.NoOpTrafficPredictionIntegration
import com.smarttraffic.core_engine.data.routing.ParetoRouteOptimizationEngine
import com.smarttraffic.core_engine.data.routing.PredictiveWeightAdjuster
import com.smarttraffic.core_engine.data.routing.RouteCandidateGenerator
import com.smarttraffic.core_engine.data.routing.RouteOptimizationEngine
import com.smarttraffic.core_engine.data.routing.RouteRankingModel
import com.smarttraffic.core_engine.data.routing.RoutingAlgorithms
import com.smarttraffic.core_engine.data.routing.TrafficPenaltyModel
import com.smarttraffic.core_engine.data.routing.TrafficPredictionIntegration
import com.smarttraffic.core_engine.data.routing.TurnCostModel
import com.smarttraffic.core_engine.data.routing.UncertaintyAwareScorer
import com.smarttraffic.core_engine.data.routing.WeatherPenaltyModel
import com.smarttraffic.core_engine.data.routing.IncidentPenaltyModel
import com.smarttraffic.core_engine.data.routing.PersonalizationLayer
import com.smarttraffic.core_engine.data.routing.RoadClassWeighting
import com.smarttraffic.core_engine.data.routing.ReliabilityScorer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RoutingEngineProvisionModule {
    @Provides
    @Singleton
    fun provideDirectedRoadGraph(): DirectedRoadGraph = DirectedRoadGraph()

    @Provides
    @Singleton
    fun provideRoutingAlgorithms(graph: DirectedRoadGraph): RoutingAlgorithms = RoutingAlgorithms(graph)

    @Provides
    @Singleton
    fun provideUncertaintyAwareScorer(): UncertaintyAwareScorer = UncertaintyAwareScorer()

    @Provides
    @Singleton
    fun provideCostFunctionBuilder(
        predictionIntegration: TrafficPredictionIntegration,
        trafficPenaltyModel: TrafficPenaltyModel,
        incidentPenaltyModel: IncidentPenaltyModel,
        weatherPenaltyModel: WeatherPenaltyModel,
        turnCostModel: TurnCostModel,
        roadClassWeighting: RoadClassWeighting,
    ): CostFunctionBuilder {
        return CostFunctionBuilder(
            predictionIntegration = predictionIntegration,
            trafficPenaltyModel = trafficPenaltyModel,
            incidentPenaltyModel = incidentPenaltyModel,
            weatherPenaltyModel = weatherPenaltyModel,
            turnCostModel = turnCostModel,
            roadClassWeighting = roadClassWeighting,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingEngineBindingModule {
    @Binds
    @Singleton
    abstract fun bindTrafficPredictionIntegration(impl: NoOpTrafficPredictionIntegration): TrafficPredictionIntegration

    @Binds
    @Singleton
    abstract fun bindTrafficPenaltyModel(impl: DefaultTrafficPenaltyModel): TrafficPenaltyModel

    @Binds
    @Singleton
    abstract fun bindIncidentPenaltyModel(impl: DefaultIncidentPenaltyModel): IncidentPenaltyModel

    @Binds
    @Singleton
    abstract fun bindWeatherPenaltyModel(impl: DefaultWeatherPenaltyModel): WeatherPenaltyModel

    @Binds
    @Singleton
    abstract fun bindTurnCostModel(impl: DefaultTurnCostModel): TurnCostModel

    @Binds
    @Singleton
    abstract fun bindRoadClassWeighting(impl: DefaultRoadClassWeighting): RoadClassWeighting

    @Binds
    @Singleton
    abstract fun bindRouteCandidateGenerator(impl: DefaultRouteCandidateGenerator): RouteCandidateGenerator

    @Binds
    @Singleton
    abstract fun bindRouteRankingModel(impl: DefaultRouteRankingModel): RouteRankingModel

    @Binds
    @Singleton
    abstract fun bindPersonalizationLayer(impl: DefaultPersonalizationLayer): PersonalizationLayer

    @Binds
    @Singleton
    abstract fun bindReliabilityScorer(impl: DefaultReliabilityScorer): ReliabilityScorer

    @Binds
    @Singleton
    abstract fun bindPredictiveWeightAdjuster(impl: DefaultPredictiveWeightAdjuster): PredictiveWeightAdjuster

    @Binds
    @Singleton
    abstract fun bindRouteOptimizationEngine(impl: ParetoRouteOptimizationEngine): RouteOptimizationEngine
}
