<?xml version="1.0" encoding="UTF-8"?>
<xsd:schema
        xmlns:xsd="http://www.w3.org/2001/XMLSchema"
        targetNamespace="http://www.example.org/bom"
        xmlns="http://www.example.org/bom"
        elementFormDefault="qualified">

    <!-- ROOT -->
    <xsd:element name="collections">
        <xsd:complexType>
            <xsd:sequence>
                <xsd:element name="request" type="Request"/>
                <xsd:element name="response" type="Response" minOccurs="0"/>
            </xsd:sequence>
        </xsd:complexType>
    </xsd:element>

    <!-- REQUEST -->
    <xsd:complexType name="Request">
        <xsd:sequence>
            <xsd:element name="customerData" type="CustomerData"/>
        </xsd:sequence>
    </xsd:complexType>

    <!-- RESPONSE (empty for now) -->
    <xsd:complexType name="Response">
        <xsd:sequence/>
    </xsd:complexType>

    <!-- YOUR EXACT FIELDS (from screenshot) -->
    <xsd:complexType name="CustomerData">
        <xsd:sequence>
            <xsd:element name="accountNumb" type="xsd:int" minOccurs="0"/>
            <xsd:element name="memberNumber" type="xsd:int" minOccurs="0"/>
            <xsd:element name="zip" type="xsd:string" minOccurs="0"/>
            <xsd:element name="accountBal" type="xsd:int" minOccurs="0"/>
            <xsd:element name="loanPurpose" type="xsd:string" minOccurs="0"/>
            <xsd:element name="loanPrincAmount" type="xsd:int" minOccurs="0"/>
            <xsd:element name="daysSinceFirstTrade" type="xsd:int" minOccurs="0"/>
            <xsd:element name="numTradesWorstRating60DaysPastDueWithin6Months" type="xsd:int" minOccurs="0"/>
            <xsd:element name="fico09Score" type="xsd:int" minOccurs="0"/>
            <xsd:element name="daysSinceLastDerog" type="xsd:int" minOccurs="0"/>
            <xsd:element name="loanTerm" type="xsd:int" minOccurs="0"/>
            <xsd:element name="referredLang" type="xsd:string" minOccurs="0"/>
            <xsd:element name="referredFlag" type="xsd:string" minOccurs="0"/>
            <xsd:element name="dealerFlag" type="xsd:string" minOccurs="0"/>
            <xsd:element name="newUsed" type="xsd:string" minOccurs="0"/>
            <xsd:element name="category3" type="xsd:string" minOccurs="0"/>
            <xsd:element name="category4" type="xsd:string" minOccurs="0"/>
            <xsd:element name="actionType" type="xsd:string" minOccurs="0"/>
        </xsd:sequence>
    </xsd:complexType>

</xsd:schema>
