# ruff: noqa
# fmt: off
# mypy: ignore-errors
# Generated from contracts/proto by regenerate.py. Do not edit.
import datetime

from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class Channel(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CHANNEL_UNSPECIFIED: _ClassVar[Channel]
    CHANNEL_MOBILE_MONEY: _ClassVar[Channel]
    CHANNEL_CARD: _ClassVar[Channel]
    CHANNEL_AGENT_BANKING: _ClassVar[Channel]
    CHANNEL_USSD: _ClassVar[Channel]
    CHANNEL_ONLINE: _ClassVar[Channel]
    CHANNEL_BANK_TRANSFER: _ClassVar[Channel]

class RiskTier(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    RISK_TIER_UNSPECIFIED: _ClassVar[RiskTier]
    RISK_TIER_LOW: _ClassVar[RiskTier]
    RISK_TIER_MEDIUM: _ClassVar[RiskTier]
    RISK_TIER_HIGH: _ClassVar[RiskTier]
CHANNEL_UNSPECIFIED: Channel
CHANNEL_MOBILE_MONEY: Channel
CHANNEL_CARD: Channel
CHANNEL_AGENT_BANKING: Channel
CHANNEL_USSD: Channel
CHANNEL_ONLINE: Channel
CHANNEL_BANK_TRANSFER: Channel
RISK_TIER_UNSPECIFIED: RiskTier
RISK_TIER_LOW: RiskTier
RISK_TIER_MEDIUM: RiskTier
RISK_TIER_HIGH: RiskTier

class Money(_message.Message):
    __slots__ = ("amount", "currency")
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    CURRENCY_FIELD_NUMBER: _ClassVar[int]
    amount: str
    currency: str
    def __init__(self, amount: _Optional[str] = ..., currency: _Optional[str] = ...) -> None: ...

class GeoPoint(_message.Message):
    __slots__ = ("latitude", "longitude")
    LATITUDE_FIELD_NUMBER: _ClassVar[int]
    LONGITUDE_FIELD_NUMBER: _ClassVar[int]
    latitude: float
    longitude: float
    def __init__(self, latitude: _Optional[float] = ..., longitude: _Optional[float] = ...) -> None: ...

class Transaction(_message.Message):
    __slots__ = ("transaction_id", "institution_id", "account_token", "counterparty_token", "amount", "amount_rwf", "channel", "merchant_category_code", "location", "device_token", "agent_token", "counterparty_country", "transaction_timestamp", "received_at")
    TRANSACTION_ID_FIELD_NUMBER: _ClassVar[int]
    INSTITUTION_ID_FIELD_NUMBER: _ClassVar[int]
    ACCOUNT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_TOKEN_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_RWF_FIELD_NUMBER: _ClassVar[int]
    CHANNEL_FIELD_NUMBER: _ClassVar[int]
    MERCHANT_CATEGORY_CODE_FIELD_NUMBER: _ClassVar[int]
    LOCATION_FIELD_NUMBER: _ClassVar[int]
    DEVICE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    AGENT_TOKEN_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_COUNTRY_FIELD_NUMBER: _ClassVar[int]
    TRANSACTION_TIMESTAMP_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_AT_FIELD_NUMBER: _ClassVar[int]
    transaction_id: str
    institution_id: str
    account_token: str
    counterparty_token: str
    amount: Money
    amount_rwf: str
    channel: Channel
    merchant_category_code: str
    location: GeoPoint
    device_token: str
    agent_token: str
    counterparty_country: str
    transaction_timestamp: _timestamp_pb2.Timestamp
    received_at: _timestamp_pb2.Timestamp
    def __init__(self, transaction_id: _Optional[str] = ..., institution_id: _Optional[str] = ..., account_token: _Optional[str] = ..., counterparty_token: _Optional[str] = ..., amount: _Optional[_Union[Money, _Mapping]] = ..., amount_rwf: _Optional[str] = ..., channel: _Optional[_Union[Channel, str]] = ..., merchant_category_code: _Optional[str] = ..., location: _Optional[_Union[GeoPoint, _Mapping]] = ..., device_token: _Optional[str] = ..., agent_token: _Optional[str] = ..., counterparty_country: _Optional[str] = ..., transaction_timestamp: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., received_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class AccountContext(_message.Message):
    __slots__ = ("tx_count_60s", "tx_count_1h", "tx_count_24h", "tx_count_7d", "amount_sum_24h_rwf", "amount_sum_7d_rwf", "unique_counterparties_24h", "mean_hourly_count_30d", "amount_median_90d_rwf", "amount_mad_90d_rwf", "amount_max_90d_rwf", "history_count_90d", "last_location", "last_transaction_at", "home_centroid_90d", "countries_seen", "account_age_days", "kyc_tier", "days_since_sim_swap", "days_since_previous_activity", "counterparty_new_for_account", "counterparty_account_age_days", "counterparty_unique_senders_24h", "counterparty_confirmed_fraud_90d", "tx_count_to_counterparty_30d", "device", "agent", "geo_cell_fraud_rate_30d", "accounts_sharing_device_or_phone", "volume_ramp_ratio_7d")
    TX_COUNT_60S_FIELD_NUMBER: _ClassVar[int]
    TX_COUNT_1H_FIELD_NUMBER: _ClassVar[int]
    TX_COUNT_24H_FIELD_NUMBER: _ClassVar[int]
    TX_COUNT_7D_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_SUM_24H_RWF_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_SUM_7D_RWF_FIELD_NUMBER: _ClassVar[int]
    UNIQUE_COUNTERPARTIES_24H_FIELD_NUMBER: _ClassVar[int]
    MEAN_HOURLY_COUNT_30D_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_MEDIAN_90D_RWF_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_MAD_90D_RWF_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_MAX_90D_RWF_FIELD_NUMBER: _ClassVar[int]
    HISTORY_COUNT_90D_FIELD_NUMBER: _ClassVar[int]
    LAST_LOCATION_FIELD_NUMBER: _ClassVar[int]
    LAST_TRANSACTION_AT_FIELD_NUMBER: _ClassVar[int]
    HOME_CENTROID_90D_FIELD_NUMBER: _ClassVar[int]
    COUNTRIES_SEEN_FIELD_NUMBER: _ClassVar[int]
    ACCOUNT_AGE_DAYS_FIELD_NUMBER: _ClassVar[int]
    KYC_TIER_FIELD_NUMBER: _ClassVar[int]
    DAYS_SINCE_SIM_SWAP_FIELD_NUMBER: _ClassVar[int]
    DAYS_SINCE_PREVIOUS_ACTIVITY_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_NEW_FOR_ACCOUNT_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_ACCOUNT_AGE_DAYS_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_UNIQUE_SENDERS_24H_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_CONFIRMED_FRAUD_90D_FIELD_NUMBER: _ClassVar[int]
    TX_COUNT_TO_COUNTERPARTY_30D_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    AGENT_FIELD_NUMBER: _ClassVar[int]
    GEO_CELL_FRAUD_RATE_30D_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTS_SHARING_DEVICE_OR_PHONE_FIELD_NUMBER: _ClassVar[int]
    VOLUME_RAMP_RATIO_7D_FIELD_NUMBER: _ClassVar[int]
    tx_count_60s: int
    tx_count_1h: int
    tx_count_24h: int
    tx_count_7d: int
    amount_sum_24h_rwf: str
    amount_sum_7d_rwf: str
    unique_counterparties_24h: int
    mean_hourly_count_30d: float
    amount_median_90d_rwf: str
    amount_mad_90d_rwf: str
    amount_max_90d_rwf: str
    history_count_90d: int
    last_location: GeoPoint
    last_transaction_at: _timestamp_pb2.Timestamp
    home_centroid_90d: GeoPoint
    countries_seen: _containers.RepeatedScalarFieldContainer[str]
    account_age_days: int
    kyc_tier: int
    days_since_sim_swap: int
    days_since_previous_activity: int
    counterparty_new_for_account: bool
    counterparty_account_age_days: int
    counterparty_unique_senders_24h: int
    counterparty_confirmed_fraud_90d: int
    tx_count_to_counterparty_30d: int
    device: DeviceContext
    agent: AgentContext
    geo_cell_fraud_rate_30d: float
    accounts_sharing_device_or_phone: int
    volume_ramp_ratio_7d: float
    def __init__(self, tx_count_60s: _Optional[int] = ..., tx_count_1h: _Optional[int] = ..., tx_count_24h: _Optional[int] = ..., tx_count_7d: _Optional[int] = ..., amount_sum_24h_rwf: _Optional[str] = ..., amount_sum_7d_rwf: _Optional[str] = ..., unique_counterparties_24h: _Optional[int] = ..., mean_hourly_count_30d: _Optional[float] = ..., amount_median_90d_rwf: _Optional[str] = ..., amount_mad_90d_rwf: _Optional[str] = ..., amount_max_90d_rwf: _Optional[str] = ..., history_count_90d: _Optional[int] = ..., last_location: _Optional[_Union[GeoPoint, _Mapping]] = ..., last_transaction_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., home_centroid_90d: _Optional[_Union[GeoPoint, _Mapping]] = ..., countries_seen: _Optional[_Iterable[str]] = ..., account_age_days: _Optional[int] = ..., kyc_tier: _Optional[int] = ..., days_since_sim_swap: _Optional[int] = ..., days_since_previous_activity: _Optional[int] = ..., counterparty_new_for_account: _Optional[bool] = ..., counterparty_account_age_days: _Optional[int] = ..., counterparty_unique_senders_24h: _Optional[int] = ..., counterparty_confirmed_fraud_90d: _Optional[int] = ..., tx_count_to_counterparty_30d: _Optional[int] = ..., device: _Optional[_Union[DeviceContext, _Mapping]] = ..., agent: _Optional[_Union[AgentContext, _Mapping]] = ..., geo_cell_fraud_rate_30d: _Optional[float] = ..., accounts_sharing_device_or_phone: _Optional[int] = ..., volume_ramp_ratio_7d: _Optional[float] = ...) -> None: ...

class DeviceContext(_message.Message):
    __slots__ = ("device_new_for_account", "accounts_per_device_7d", "device_changes_24h", "device_age_days")
    DEVICE_NEW_FOR_ACCOUNT_FIELD_NUMBER: _ClassVar[int]
    ACCOUNTS_PER_DEVICE_7D_FIELD_NUMBER: _ClassVar[int]
    DEVICE_CHANGES_24H_FIELD_NUMBER: _ClassVar[int]
    DEVICE_AGE_DAYS_FIELD_NUMBER: _ClassVar[int]
    device_new_for_account: bool
    accounts_per_device_7d: int
    device_changes_24h: int
    device_age_days: int
    def __init__(self, device_new_for_account: _Optional[bool] = ..., accounts_per_device_7d: _Optional[int] = ..., device_changes_24h: _Optional[int] = ..., device_age_days: _Optional[int] = ...) -> None: ...

class AgentContext(_message.Message):
    __slots__ = ("float_utilisation_ratio", "cashout_count_1h", "unique_customers_1h", "distance_from_registered_km")
    FLOAT_UTILISATION_RATIO_FIELD_NUMBER: _ClassVar[int]
    CASHOUT_COUNT_1H_FIELD_NUMBER: _ClassVar[int]
    UNIQUE_CUSTOMERS_1H_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FROM_REGISTERED_KM_FIELD_NUMBER: _ClassVar[int]
    float_utilisation_ratio: float
    cashout_count_1h: int
    unique_customers_1h: int
    distance_from_registered_km: float
    def __init__(self, float_utilisation_ratio: _Optional[float] = ..., cashout_count_1h: _Optional[int] = ..., unique_customers_1h: _Optional[int] = ..., distance_from_registered_km: _Optional[float] = ...) -> None: ...

class ScoreRequest(_message.Message):
    __slots__ = ("transaction", "configured_limits", "traceparent")
    TRANSACTION_FIELD_NUMBER: _ClassVar[int]
    CONFIGURED_LIMITS_FIELD_NUMBER: _ClassVar[int]
    TRACEPARENT_FIELD_NUMBER: _ClassVar[int]
    transaction: Transaction
    configured_limits: _containers.RepeatedCompositeFieldContainer[Money]
    traceparent: str
    def __init__(self, transaction: _Optional[_Union[Transaction, _Mapping]] = ..., configured_limits: _Optional[_Iterable[_Union[Money, _Mapping]]] = ..., traceparent: _Optional[str] = ...) -> None: ...

class FeatureValue(_message.Message):
    __slots__ = ("number", "category", "missing")
    class Missing(_message.Message):
        __slots__ = ()
        def __init__(self) -> None: ...
    NUMBER_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    MISSING_FIELD_NUMBER: _ClassVar[int]
    number: float
    category: str
    missing: FeatureValue.Missing
    def __init__(self, number: _Optional[float] = ..., category: _Optional[str] = ..., missing: _Optional[_Union[FeatureValue.Missing, _Mapping]] = ...) -> None: ...

class ShapContribution(_message.Message):
    __slots__ = ("feature", "value", "shap", "increases_risk", "template_key")
    FEATURE_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    SHAP_FIELD_NUMBER: _ClassVar[int]
    INCREASES_RISK_FIELD_NUMBER: _ClassVar[int]
    TEMPLATE_KEY_FIELD_NUMBER: _ClassVar[int]
    feature: str
    value: FeatureValue
    shap: float
    increases_risk: bool
    template_key: str
    def __init__(self, feature: _Optional[str] = ..., value: _Optional[_Union[FeatureValue, _Mapping]] = ..., shap: _Optional[float] = ..., increases_risk: _Optional[bool] = ..., template_key: _Optional[str] = ...) -> None: ...

class StageTiming(_message.Message):
    __slots__ = ("stage", "milliseconds")
    class Stage(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        STAGE_UNSPECIFIED: _ClassVar[StageTiming.Stage]
        STAGE_FEATURES: _ClassVar[StageTiming.Stage]
        STAGE_ENSEMBLE: _ClassVar[StageTiming.Stage]
        STAGE_CALIBRATION: _ClassVar[StageTiming.Stage]
        STAGE_ISOLATION_FOREST: _ClassVar[StageTiming.Stage]
        STAGE_SHAP: _ClassVar[StageTiming.Stage]
    STAGE_UNSPECIFIED: StageTiming.Stage
    STAGE_FEATURES: StageTiming.Stage
    STAGE_ENSEMBLE: StageTiming.Stage
    STAGE_CALIBRATION: StageTiming.Stage
    STAGE_ISOLATION_FOREST: StageTiming.Stage
    STAGE_SHAP: StageTiming.Stage
    STAGE_FIELD_NUMBER: _ClassVar[int]
    MILLISECONDS_FIELD_NUMBER: _ClassVar[int]
    stage: StageTiming.Stage
    milliseconds: float
    def __init__(self, stage: _Optional[_Union[StageTiming.Stage, str]] = ..., milliseconds: _Optional[float] = ...) -> None: ...

class ScoringResult(_message.Message):
    __slots__ = ("scoring_result_id", "transaction_id", "ensemble_score", "xgboost_score", "lightgbm_score", "anomaly_score", "anomaly_raw", "model_risk_tier", "shap_top5", "feature_vector", "shap_all", "shap_base_value", "final_margin", "model_version", "feature_registry_version", "scoring_duration_ms", "stage_timings", "account_context", "feature_store_degraded")
    class FeatureVectorEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: FeatureValue
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[FeatureValue, _Mapping]] = ...) -> None: ...
    SCORING_RESULT_ID_FIELD_NUMBER: _ClassVar[int]
    TRANSACTION_ID_FIELD_NUMBER: _ClassVar[int]
    ENSEMBLE_SCORE_FIELD_NUMBER: _ClassVar[int]
    XGBOOST_SCORE_FIELD_NUMBER: _ClassVar[int]
    LIGHTGBM_SCORE_FIELD_NUMBER: _ClassVar[int]
    ANOMALY_SCORE_FIELD_NUMBER: _ClassVar[int]
    ANOMALY_RAW_FIELD_NUMBER: _ClassVar[int]
    MODEL_RISK_TIER_FIELD_NUMBER: _ClassVar[int]
    SHAP_TOP5_FIELD_NUMBER: _ClassVar[int]
    FEATURE_VECTOR_FIELD_NUMBER: _ClassVar[int]
    SHAP_ALL_FIELD_NUMBER: _ClassVar[int]
    SHAP_BASE_VALUE_FIELD_NUMBER: _ClassVar[int]
    FINAL_MARGIN_FIELD_NUMBER: _ClassVar[int]
    MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    FEATURE_REGISTRY_VERSION_FIELD_NUMBER: _ClassVar[int]
    SCORING_DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    STAGE_TIMINGS_FIELD_NUMBER: _ClassVar[int]
    ACCOUNT_CONTEXT_FIELD_NUMBER: _ClassVar[int]
    FEATURE_STORE_DEGRADED_FIELD_NUMBER: _ClassVar[int]
    scoring_result_id: str
    transaction_id: str
    ensemble_score: float
    xgboost_score: float
    lightgbm_score: float
    anomaly_score: float
    anomaly_raw: float
    model_risk_tier: RiskTier
    shap_top5: _containers.RepeatedCompositeFieldContainer[ShapContribution]
    feature_vector: _containers.MessageMap[str, FeatureValue]
    shap_all: _containers.RepeatedCompositeFieldContainer[ShapContribution]
    shap_base_value: float
    final_margin: float
    model_version: str
    feature_registry_version: str
    scoring_duration_ms: int
    stage_timings: _containers.RepeatedCompositeFieldContainer[StageTiming]
    account_context: AccountContext
    feature_store_degraded: bool
    def __init__(self, scoring_result_id: _Optional[str] = ..., transaction_id: _Optional[str] = ..., ensemble_score: _Optional[float] = ..., xgboost_score: _Optional[float] = ..., lightgbm_score: _Optional[float] = ..., anomaly_score: _Optional[float] = ..., anomaly_raw: _Optional[float] = ..., model_risk_tier: _Optional[_Union[RiskTier, str]] = ..., shap_top5: _Optional[_Iterable[_Union[ShapContribution, _Mapping]]] = ..., feature_vector: _Optional[_Mapping[str, FeatureValue]] = ..., shap_all: _Optional[_Iterable[_Union[ShapContribution, _Mapping]]] = ..., shap_base_value: _Optional[float] = ..., final_margin: _Optional[float] = ..., model_version: _Optional[str] = ..., feature_registry_version: _Optional[str] = ..., scoring_duration_ms: _Optional[int] = ..., stage_timings: _Optional[_Iterable[_Union[StageTiming, _Mapping]]] = ..., account_context: _Optional[_Union[AccountContext, _Mapping]] = ..., feature_store_degraded: _Optional[bool] = ...) -> None: ...

class ScoreResponse(_message.Message):
    __slots__ = ("result",)
    RESULT_FIELD_NUMBER: _ClassVar[int]
    result: ScoringResult
    def __init__(self, result: _Optional[_Union[ScoringResult, _Mapping]] = ...) -> None: ...

class GetModelStatusRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class GetModelStatusResponse(_message.Message):
    __slots__ = ("status", "production_model_version", "shadow_model_version", "feature_registry_version")
    class Status(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        STATUS_UNSPECIFIED: _ClassVar[GetModelStatusResponse.Status]
        STATUS_SERVING: _ClassVar[GetModelStatusResponse.Status]
        STATUS_DEGRADED: _ClassVar[GetModelStatusResponse.Status]
        STATUS_NOT_SERVING: _ClassVar[GetModelStatusResponse.Status]
    STATUS_UNSPECIFIED: GetModelStatusResponse.Status
    STATUS_SERVING: GetModelStatusResponse.Status
    STATUS_DEGRADED: GetModelStatusResponse.Status
    STATUS_NOT_SERVING: GetModelStatusResponse.Status
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PRODUCTION_MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    SHADOW_MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    FEATURE_REGISTRY_VERSION_FIELD_NUMBER: _ClassVar[int]
    status: GetModelStatusResponse.Status
    production_model_version: str
    shadow_model_version: str
    feature_registry_version: str
    def __init__(self, status: _Optional[_Union[GetModelStatusResponse.Status, str]] = ..., production_model_version: _Optional[str] = ..., shadow_model_version: _Optional[str] = ..., feature_registry_version: _Optional[str] = ...) -> None: ...
